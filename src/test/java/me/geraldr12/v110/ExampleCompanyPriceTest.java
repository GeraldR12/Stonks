package me.geraldr12.v110;

import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.v110.MigrationState.MigrationException;
import me.geraldr12.v110.destination.CompanyDestination;
import me.geraldr12.v110.destination.PlayerDestination;
import me.geraldr12.v110.destination.SignDestination;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExampleCompanyPriceTest {
    @TempDir Path temp;

    @TestFactory
    Stream<DynamicTest> recognizesOldAndNewExamplePrices() {
        return Stream.of(40.0, 1000.0).map(price -> DynamicTest.dynamicTest("example " + price, () -> {
            Path directory = Files.createDirectory(temp.resolve("price-" + price));
            Map<Integer, CompanyDao> rows = new HashMap<>();
            CompanyDestination companies = destination(rows, price);

            assertDoesNotThrow(() -> coordinator(directory, companies).run());

            verify(companies).delete(0);
            assertFalse(rows.containsKey(0));
            assertEquals("Migrated", rows.get(5).getName());
            assertTrue(Files.readString(directory.resolve("migration/v110/state.json"))
                    .contains("\"phase\":\"DONE\""));
        }));
    }

    @Test
    void doesNotRecognizeModifiedExamplePrice() throws Exception {
        Path directory = Files.createDirectory(temp.resolve("price-41"));
        Map<Integer, CompanyDao> rows = new HashMap<>();
        CompanyDestination companies = destination(rows, 41);

        MigrationException failure = assertThrows(MigrationException.class,
                () -> coordinator(directory, companies).run());

        assertEquals("Conflict: company 0 is not the default example", failure.getMessage());
        verify(companies, never()).delete(anyInt());
        verify(companies, never()).save(any());
        assertEquals(41, rows.get(0).getInitialSharePrice());
        assertEquals(1, rows.size());
    }

    private CompanyDestination destination(Map<Integer, CompanyDao> rows, double price) {
        rows.put(0, CompanyDao.builder().id(0).name("Blocks Corp").risk(3)
                .totalShares(100).availableShares(99).initialSharePrice(price).currentSharePrice(price).build());
        CompanyDestination companies = mock(CompanyDestination.class);
        when(companies.findById(anyInt())).thenAnswer(invocation ->
                Optional.ofNullable(rows.get(invocation.getArgument(0))));
        when(companies.exists(anyInt())).thenAnswer(invocation -> rows.containsKey(invocation.getArgument(0)));
        when(companies.delete(anyInt())).thenAnswer(invocation -> rows.remove(invocation.getArgument(0)) != null);
        when(companies.save(any())).thenAnswer(invocation -> {
            CompanyDao company = invocation.getArgument(0);
            rows.put(company.getId(), company);
            return true;
        });
        return companies;
    }

    private V110MigrationCoordinator coordinator(Path directory, CompanyDestination companies) throws Exception {
        Files.writeString(directory.resolve("companies.yml"), "legacy source");
        MemoryConfiguration legacy = new MemoryConfiguration();
        legacy.set("Companies.5.Name", "Migrated");
        legacy.set("Companies.5.Price", 40);
        legacy.set("Companies.5.Risk", 1);
        legacy.set("Companies.5.AvailableStocks", 10);
        return new V110MigrationCoordinator(directory.toFile(), Logger.getAnonymousLogger(), companies,
                mock(PlayerDestination.class), mock(SignDestination.class), name -> null,
                (section, path) -> null, file -> legacy, MigrationFileOps.DEFAULT);
    }
}

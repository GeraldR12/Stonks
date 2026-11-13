package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.persistence.MarketTransactionStatus;
import me.geraldr12.utils.MarketTransactionLocks;
import me.geraldr12.utils.Messages;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CreateSharePriceLimitTest {
    @TempDir Path temp;

    private final Stonks plugin = mock(Stonks.class);
    private final Player player = mock(Player.class);
    private final Economy economy = mock(Economy.class);
    private final CompaniesService companies = mock(CompaniesService.class);
    private final PlayersService players = mock(PlayersService.class);
    private final Logger logger = mock(Logger.class);
    private final MemoryConfiguration config = new MemoryConfiguration();
    private final UUID playerId = UUID.randomUUID();
    private MarketTransactionJournal journal;
    private CreateCommand command;

    @BeforeEach
    void setUp() {
        Messages messages = mock(Messages.class);
        when(messages.getPluginPrefix()).thenReturn("");
        when(messages.getCreatedCompany()).thenReturn("Created {0}");
        when(plugin.getMessages()).thenReturn(messages);
        FileConfiguration fileConfig = mock(FileConfiguration.class);
        when(fileConfig.get(anyString(), any())).thenAnswer(invocation ->
                config.get(invocation.getArgument(0), invocation.getArgument(1)));
        when(plugin.getConfig()).thenReturn(fileConfig);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getEconomy()).thenReturn(economy);
        when(plugin.getCompaniesService()).thenReturn(companies);
        when(plugin.getPlayersService()).thenReturn(players);
        when(plugin.isJournalReady()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(playerId);
        journal = spy(new MarketTransactionJournal(temp.toFile()));
        when(plugin.getTransactionJournal()).thenReturn(journal);
        when(companies.getCompanyCreationTax(eq(10L), anyDouble(), eq(1))).thenReturn(20.0);
        when(companies.reserveCompanyId(any())).thenAnswer(invocation -> {
            CompanyDao company = invocation.getArgument(0);
            company.setId(1);
            return company;
        });
        when(companies.saveReservedCompany(any())).thenReturn(true);
        when(companies.removeSharesFromCompany(1, 10)).thenReturn(true);
        when(players.addSharesToPlayer(eq(playerId), any(), eq(10))).thenReturn(true);
        when(economy.fractionalDigits()).thenReturn(2);
        when(economy.has(player, 20.0)).thenReturn(true);
        when(economy.withdrawPlayer(player, 20.0))
                .thenReturn(new EconomyResponse(20, 100, EconomyResponse.ResponseType.SUCCESS, null));
        MarketTransactionLocks.initialize(journal);
        assertTrue(MarketTransactionLocks.activate(plugin, journal));
        clearInvocations(plugin, journal);
        command = new CreateCommand(plugin);
    }

    @AfterEach
    void tearDown() {
        try {
            assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
        } finally {
            MarketTransactionLocks.revoke();
        }
    }

    @TestFactory
    Stream<DynamicTest> configuredRangeRejectsWithoutFinancialEffects() {
        return Stream.of(29.99, 50.01).map(price -> DynamicTest.dynamicTest("reject " + price, () -> {
            limits(30, 50);
            reject(price, "Share price must be between 30 and 50.");
            clearInvocations(player);
        }));
    }

    @TestFactory
    Stream<DynamicTest> inclusiveBoundariesFollowNormalCreation() {
        return Stream.of(30.0, 50.0).map(price -> DynamicTest.dynamicTest("accept " + price, () -> {
            limits(30, 50);
            accept(price);
            clearInvocations(economy, companies, players, player, journal);
        }));
    }

    @Test
    void missingKeysUseDefaultRange() {
        reject(29.99, "Share price must be between 30 and 50.");
        clearInvocations(player);
        reject(50.01, "Share price must be between 30 and 50.");
        accept(40);
    }

    @Test
    void missingMinimumUsesThirty() {
        config.set("Stonks.Limits.MaxSharePrice", 60);
        reject(29.99, "Share price must be between 30 and 60.");
        accept(60);
    }

    @Test
    void missingMaximumUsesFifty() {
        config.set("Stonks.Limits.MinSharePrice", 20);
        reject(50.01, "Share price must be between 20 and 50.");
        accept(20);
    }

    @Test
    void disabledMinimumKeepsMaximum() {
        limits(-1, 50);
        reject(50.01, "Share price must be at most 50.");
        accept(1);
    }

    @Test
    void disabledMaximumKeepsMinimum() {
        limits(30, -1);
        reject(29.99, "Share price must be at least 30.");
        accept(100);
    }

    @Test
    void bothSidesCanBeDisabled() {
        limits(-1, -1);
        accept(1);
        verify(logger, never()).warning(anyString());
    }

    @Test
    void fractionalLimitsAreNotRoundedInMessage() {
        limits(30.125, 49.875);
        reject(30.12, "Share price must be between 30.125 and 49.875.");
    }

    @Test
    void equalLimitsAllowOnlyThatPrice() {
        limits(40, 40);
        reject(39.99, "Share price must be between 40 and 40.");
        accept(40);
    }

    @Test
    void invertedRangeDoesNotBlockAndWarnsOnce() {
        limits(60, 30);
        accept(10);
        clearInvocations(economy, companies, players, player, journal);
        accept(70);
        verify(logger, times(1)).warning(contains("share price"));
    }

    @TestFactory
    Stream<DynamicTest> nonFiniteLimitsDoNotBlockAndWarnOnce() {
        return Stream.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
                .flatMap(value -> Stream.of(true, false).map(minimum -> DynamicTest.dynamicTest(
                        (minimum ? "minimum " : "maximum ") + value, () -> {
                            limits(minimum ? value : 30, minimum ? 50 : value);
                            command = new CreateCommand(plugin);
                            accept(10);
                            clearInvocations(economy, companies, players, player, journal);
                            accept(70);
                            verify(logger, times(1)).warning(contains("share price"));
                            clearInvocations(economy, companies, players, player, journal, logger);
                        })));
    }

    private void limits(double minimum, double maximum) {
        config.set("Stonks.Limits.MinSharePrice", minimum);
        config.set("Stonks.Limits.MaxSharePrice", maximum);
    }

    private void reject(double price, String message) {
        assertTrue(command.onCommand(player, new String[]{"Acme", "1", "10", Double.toString(price)}));
        verify(player).sendMessage(message);
        verifyNoInteractions(economy, companies, players, journal);
        verify(plugin, never()).getTransactionJournal();
        assertFalse(Files.exists(temp.resolve("transactions")));
        assertFalse(Files.exists(temp.resolve("companies")));
    }

    private void accept(double price) {
        assertTrue(command.onCommand(player, new String[]{"Acme", "1", "10", Double.toString(price)}));
        verify(economy).withdrawPlayer(player, 20.0);
        verify(companies).saveReservedCompany(argThat(company ->
                company.getInitialSharePrice() == price && company.getCurrentSharePrice() == price));
        verify(players).addSharesToPlayer(eq(playerId), any(), eq(10));
        verify(journal).complete(argThat(record -> record.getStatus() == MarketTransactionStatus.COMPLETED));
        verify(player).sendMessage("Created Acme");
    }
}

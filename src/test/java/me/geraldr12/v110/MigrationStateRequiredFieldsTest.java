package me.geraldr12.v110;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MigrationStateRequiredFieldsTest {
    @TempDir Path directory;

    @TestFactory
    Stream<DynamicTest> rejectsMissingAndNullRequiredFields() {
        String complete = "{\"version\":1,\"phase\":\"DONE\",\"startedAt\":\"start\",\"updatedAt\":\"update\"}";
        List<String> fields = List.of("\"version\":1", "\"phase\":\"DONE\"", "\"startedAt\":\"start\"", "\"updatedAt\":\"update\"");
        Stream<String> incomplete = fields.stream().flatMap(field -> Stream.of(
                complete.replace(field + ",", "").replace("," + field, ""),
                complete.replace(field, field.substring(0, field.indexOf(':') + 1) + "null")));
        return Stream.concat(Stream.of("{}", "{\"phase\":\"DONE\"}"), incomplete)
                .map(json -> DynamicTest.dynamicTest(json, () -> {
                    Path state = directory.resolve("migration/v110/state.json");
                    Files.createDirectories(state.getParent());
                    Files.writeString(state, json);
                    assertThrows(MigrationState.MigrationException.class,
                            () -> MigrationState.load(directory.toFile(), MigrationFileOps.DEFAULT));
                    assertEquals(json, Files.readString(state));
                }));
    }

    @Test
    void persistedStateStillRoundTrips() {
        MigrationState state = MigrationState.load(directory.toFile(), MigrationFileOps.DEFAULT);
        state.advanceTo(MigrationState.Phase.APPLY);
        state.recordFailure("interrupted");
        MigrationState loaded = MigrationState.load(directory.toFile(), MigrationFileOps.DEFAULT);
        assertEquals(MigrationState.Phase.APPLY, loaded.getPhase());
        assertEquals("APPLY", loaded.getFailurePhase());
        assertEquals("interrupted", loaded.getFailureReason());
        loaded.advanceTo(MigrationState.Phase.DONE);
        assertTrue(MigrationState.load(directory.toFile(), MigrationFileOps.DEFAULT).isDone());
    }
}

package me.geraldr12.v110.destination;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import me.geraldr12.v110.MigrationFileOps;
import me.geraldr12.v110.MigrationState.MigrationException;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public final class CompanyIdCounter {

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

    private CompanyIdCounter() {
    }

    public static void raiseTo(File dataFile, long target, MigrationFileOps fileOps) {
        if (!dataFile.exists()) {
            return;
        }
        try {
            JsonNode nextId = MAPPER.readTree(dataFile).path("nextId");
            if (!nextId.isIntegralNumber()) {
                throw new MigrationException("Company id counter has no valid nextId", null);
            }
            if (nextId.asLong() < target) {
                fileOps.writeAtomically(dataFile.toPath(), MAPPER.writeValueAsBytes(Map.of("nextId", target)));
            }
        } catch (IOException e) {
            throw new MigrationException("Company id counter could not be updated: " + e.getMessage(), e);
        }
    }
}

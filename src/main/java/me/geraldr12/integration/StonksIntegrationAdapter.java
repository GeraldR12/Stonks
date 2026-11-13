package me.geraldr12.integration;

import java.util.Set;

public interface StonksIntegrationAdapter {
    String id();

    IntegrationStatus status();

    Set<AdapterCapability> capabilities();

    String version();
}

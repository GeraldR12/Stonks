package me.geraldr12.integration;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AdvancedJobsAdapterMatrixTest {
    @Test
    void missingPluginIsMissing() {
        assertEquals(IntegrationStatus.PLUGIN_MISSING,
                AdvancedJobsAdapter.resolveStatus(null));
    }

    @Test
    void presentPluginRemainsUnsupportedWithoutDocumentedApi() {
        assertEquals(IntegrationStatus.API_UNSUPPORTED,
                AdvancedJobsAdapter.resolveStatus(mock(Plugin.class)));
    }

    @Test
    void versionReportingUsesPluginDescription() {
        Plugin plugin = mock(Plugin.class);
        org.mockito.Mockito.when(plugin.getDescription()).thenReturn(
                new org.bukkit.plugin.PluginDescriptionFile("AdvancedJobs", "1.9.6", "Main"));
        AdvancedJobsAdapter adapter = new AdvancedJobsAdapter(plugin);
        assertEquals("1.9.6", adapter.version());
    }
}

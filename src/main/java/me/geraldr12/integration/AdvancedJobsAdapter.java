package me.geraldr12.integration;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Set;

public final class AdvancedJobsAdapter implements StonksIntegrationAdapter {
    private final IntegrationStatus status;
    private final String version;

    public AdvancedJobsAdapter() {
        this(Bukkit.getPluginManager().getPlugin("AdvancedJobs"));
    }

    AdvancedJobsAdapter(Plugin plugin) {
        version = plugin == null ? "unknown" : plugin.getDescription().getVersion();
        status = resolveStatus(plugin);
    }

    static IntegrationStatus resolveStatus(Plugin plugin) {
        return plugin == null ? IntegrationStatus.PLUGIN_MISSING : IntegrationStatus.API_UNSUPPORTED;
    }

    @Override
    public String id() {
        return "AdvancedJobs";
    }

    @Override
    public IntegrationStatus status() {
        return status;
    }

    @Override
    public Set<AdapterCapability> capabilities() {
        return Set.of();
    }

    @Override
    public String version() {
        return version;
    }
}

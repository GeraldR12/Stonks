package me.geraldr12.integration;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Set;
import java.util.function.BooleanSupplier;

public final class VaultAdapter implements StonksIntegrationAdapter {
    private final IntegrationStatus status;
    private final String version;
    private final Economy economy;
    private final BooleanSupplier primaryThread;

    public VaultAdapter() {
        var vault = Bukkit.getPluginManager().getPlugin("Vault");
        version = vault == null ? "unknown" : vault.getDescription().getVersion();
        RegisteredServiceProvider<Economy> registration = vault == null
                ? null : Bukkit.getServicesManager().getRegistration(Economy.class);
        economy = registration == null ? null : registration.getProvider();
        status = vault == null
                ? IntegrationStatus.PLUGIN_MISSING
                : economy == null ? IntegrationStatus.DATA_UNAVAILABLE : IntegrationStatus.AVAILABLE;
        primaryThread = Bukkit::isPrimaryThread;
    }

    VaultAdapter(Economy economy, IntegrationStatus status, String version) {
        this(economy, status, version, Bukkit::isPrimaryThread);
    }

    VaultAdapter(Economy economy, IntegrationStatus status, String version,
                 BooleanSupplier primaryThread) {
        this.economy = economy;
        this.status = status;
        this.version = version;
        this.primaryThread = primaryThread;
    }

    static IntegrationStatus resolveStatus(Object vault, Economy economy) {
        if (vault == null) return IntegrationStatus.PLUGIN_MISSING;
        return economy == null ? IntegrationStatus.DATA_UNAVAILABLE : IntegrationStatus.AVAILABLE;
    }

    static double validateBalance(double balance) {
        if (!Double.isFinite(balance) || balance < 0.0) {
            throw new IllegalStateException("Vault returned an invalid balance");
        }
        return balance;
    }

    @Override
    public String id() {
        return "Vault";
    }

    @Override
    public IntegrationStatus status() {
        return status;
    }

    @Override
    public Set<AdapterCapability> capabilities() {
        return status == IntegrationStatus.AVAILABLE
                ? Set.of(AdapterCapability.ECONOMY_PROVIDER, AdapterCapability.ECONOMY_BALANCE)
                : Set.of();
    }

    @Override
    public String version() {
        return version;
    }

    public String providerName() {
        if (economy == null) throw new IllegalStateException("Vault economy is unavailable");
        return economy.getName();
    }

    public double balance(org.bukkit.OfflinePlayer player) {
        if (!primaryThread.getAsBoolean()) {
            throw new IllegalStateException("Vault balances must be read on the main thread");
        }
        if (economy == null) throw new IllegalStateException("Vault economy is unavailable");
        double balance = economy.getBalance(player);
        return validateBalance(balance);
    }
}

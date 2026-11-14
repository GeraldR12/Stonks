package me.geraldr12.economy;

import me.geraldr12.Stonks;
import me.geraldr12.data.services.TreasuryService;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;

/**
 * Deposits treasury fees through Vault into a named, non-player account such as TheNewEconomy's
 * {@code Server_Account} ({@code Core.Server.Account.Enabled: true} in TNE's config).
 */
public class VaultTreasuryGateway implements TreasuryService.Gateway {

    private final Stonks plugin;

    public VaultTreasuryGateway(Stonks plugin) {
        this.plugin = plugin;
    }

    @Override
    public String account() {
        return plugin.getConfig() == null ? "" : plugin.getConfig().getString("Stonks.Treasury.Account", "");
    }

    @Override
    @SuppressWarnings("deprecation") // Vault only reaches non-player accounts through the name-based methods.
    public boolean deposit(String account, double amount) {
        Economy economy = plugin.getEconomy();
        if (economy == null || account == null || account.isBlank()) return false;
        if (!economy.hasAccount(account)) {
            plugin.getLogger().warning("Treasury account '" + account + "' does not exist in the economy plugin;"
                    + " fees are kept as owed until it does.");
            return false;
        }
        EconomyResponse response = economy.depositPlayer(account, amount);
        return response != null && response.transactionSuccess();
    }

    @Override
    @SuppressWarnings("deprecation")
    public double balance(String account) {
        Economy economy = plugin.getEconomy();
        if (economy == null || account == null || account.isBlank() || !economy.hasAccount(account)) return Double.NaN;
        return economy.getBalance(account);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean withdraw(String account, double amount) {
        Economy economy = plugin.getEconomy();
        if (economy == null || account == null || account.isBlank() || !economy.hasAccount(account)) return false;
        EconomyResponse response = economy.withdrawPlayer(account, amount);
        return response != null && response.transactionSuccess();
    }

    @Override
    public int fractionalDigits() {
        Economy economy = plugin.getEconomy();
        return economy == null ? 0 : economy.fractionalDigits();
    }
}

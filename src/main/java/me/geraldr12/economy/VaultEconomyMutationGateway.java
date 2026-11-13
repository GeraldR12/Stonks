package me.geraldr12.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.function.BooleanSupplier;

public final class VaultEconomyMutationGateway {
    private static final int MAX_DIAGNOSTIC_LENGTH = 256;

    private final Economy economy;
    private final BooleanSupplier primaryThread;

    public VaultEconomyMutationGateway(Economy economy) {
        this(economy, VaultEconomyMutationGateway::isPrimaryThread);
    }

    private static boolean isPrimaryThread() {
        try {
            return Bukkit.isPrimaryThread();
        } catch (IllegalStateException | NullPointerException e) {
            return false;
        }
    }

    public VaultEconomyMutationGateway(Economy economy, BooleanSupplier primaryThread) {
        this.economy = economy;
        this.primaryThread = primaryThread == null ? () -> false : primaryThread;
    }

    public EconomyMutationResult withdraw(OfflinePlayer player, double amount) {
        validate(player, amount);
        requirePrimaryThread();
        return invoke(player, amount, true);
    }

    public EconomyMutationResult deposit(OfflinePlayer player, double amount) {
        validate(player, amount);
        requirePrimaryThread();
        return invoke(player, amount, false);
    }

    private EconomyMutationResult invoke(OfflinePlayer player, double amount, boolean withdraw) {
        if (economy == null) {
            return result(EconomyMutationOutcome.AMBIGUOUS, amount, null, null,
                    "Vault economy is unavailable");
        }
        try {
            double balanceBefore = economy.getBalance(player);
            EconomyResponse response = withdraw
                    ? economy.withdrawPlayer(player, amount)
                    : economy.depositPlayer(player, amount);
            if (response == null) {
                return result(EconomyMutationOutcome.AMBIGUOUS, amount, null, null,
                        "Vault returned no response");
            }
            if (!response.transactionSuccess()) {
                double balanceAfter = economy.getBalance(player);
                EconomyMutationOutcome outcome = response.type == EconomyResponse.ResponseType.FAILURE
                        && Double.isFinite(balanceBefore) && Double.isFinite(balanceAfter)
                        && balanceBefore == balanceAfter
                        ? EconomyMutationOutcome.DEFINITE_REJECTED : EconomyMutationOutcome.AMBIGUOUS;
                return result(outcome, amount, response.amount,
                        response, response.errorMessage);
            }
            if (!Double.isFinite(response.amount) || response.amount <= 0.0
                    || Double.compare(response.amount, amount) != 0) {
                return result(EconomyMutationOutcome.AMBIGUOUS, amount, response.amount,
                        response, "Vault reported an invalid or mismatched amount");
            }
            return result(EconomyMutationOutcome.CONFIRMED, amount, response.amount,
                    response, null);
        } catch (RuntimeException e) {
            return result(EconomyMutationOutcome.AMBIGUOUS, amount, null, null,
                    e.getClass().getSimpleName());
        }
    }

    private EconomyMutationResult result(EconomyMutationOutcome outcome, double requested,
                                         Double providerAmount, EconomyResponse response,
                                         String diagnostic) {
        return new EconomyMutationResult(outcome, requested, providerAmount,
                providerName(), response == null ? null : response.type,
                sanitize(diagnostic));
    }

    private String providerName() {
        if (economy == null) return null;
        try {
            return sanitize(economy.getName());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void validate(OfflinePlayer player, double amount) {
        if (player == null) throw new IllegalArgumentException("Player is required");
        if (!Double.isFinite(amount) || amount <= 0.0) {
            throw new IllegalArgumentException("Amount must be finite and positive");
        }
    }

    public void requirePrimaryThread() {
        if (!primaryThread.getAsBoolean()) {
            throw new IllegalStateException("Vault economy mutations must run on the main thread");
        }
    }

    private String sanitize(String value) {
        if (value == null) return null;
        String sanitized = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return sanitized.length() <= MAX_DIAGNOSTIC_LENGTH
                ? sanitized : sanitized.substring(0, MAX_DIAGNOSTIC_LENGTH);
    }
}

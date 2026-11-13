package me.geraldr12.economy;

import net.milkbowl.vault.economy.EconomyResponse;

public record EconomyMutationResult(
        EconomyMutationOutcome outcome,
        double requestedAmount,
        Double providerAmount,
        String providerName,
        EconomyResponse.ResponseType responseType,
        String diagnostic) {

    public EconomyMutationResult {
        if (outcome == null) throw new IllegalArgumentException("Outcome is required");
        if (!Double.isFinite(requestedAmount) || requestedAmount <= 0.0) {
            throw new IllegalArgumentException("Requested amount must be finite and positive");
        }
        if (diagnostic != null && diagnostic.length() > 256) {
            diagnostic = diagnostic.substring(0, 256);
        }
    }
}

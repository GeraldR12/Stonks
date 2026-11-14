package me.geraldr12.persistence;

import lombok.Data;

import java.util.UUID;

@Data
public class MarketTransactionEffect {
    private String effectId;
    private String transactionId;
    private MarketTransactionEffectType type;
    private MarketTransactionEffectState state;
    private int sequence;
    private long requestedAt;
    private long confirmedAt;
    private String provider;
    private String providerReference;
    private String reason;
    private double amount;
    private int quantity;

    public MarketTransactionEffect() {
    }

    public static MarketTransactionEffect requested(String transactionId,
                                                    MarketTransactionEffectType type,
                                                    int sequence,
                                                    double amount,
                                                    int quantity) {
        MarketTransactionEffect effect = new MarketTransactionEffect();
        effect.effectId = UUID.randomUUID().toString();
        effect.transactionId = transactionId;
        effect.type = type;
        effect.state = MarketTransactionEffectState.REQUESTED;
        effect.sequence = sequence;
        effect.requestedAt = System.currentTimeMillis();
        effect.amount = amount;
        effect.quantity = quantity;
        return effect;
    }
}

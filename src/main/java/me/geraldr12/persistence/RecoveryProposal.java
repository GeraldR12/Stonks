package me.geraldr12.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

public final class RecoveryProposal {
    private final String proposalId;
    private final String transactionId;
    private final String idempotencyKey;
    private final List<String> targetEffectIds;
    private final List<MarketTransactionEffectType> targetEffectTypes;
    private final RecoveryProposalDisposition disposition;
    private final RecoveryProposalStatus status;
    private final String transactionFingerprint;
    private final List<String> observationIds;
    private final List<String> evidenceIds;
    private final double expectedAmount;
    private final int expectedQuantity;
    private final String provenance;
    private final long proposedAt;
    private final long recordedAt;
    private final long expiresAt;
    private final String diagnostic;

    @JsonCreator
    public RecoveryProposal(@JsonProperty("proposalId") String proposalId,
                            @JsonProperty("transactionId") String transactionId,
                            @JsonProperty("idempotencyKey") String idempotencyKey,
                            @JsonProperty("targetEffectIds") List<String> targetEffectIds,
                            @JsonProperty("targetEffectTypes") List<MarketTransactionEffectType> targetEffectTypes,
                            @JsonProperty("disposition") RecoveryProposalDisposition disposition,
                            @JsonProperty("status") RecoveryProposalStatus status,
                            @JsonProperty("transactionFingerprint") String transactionFingerprint,
                            @JsonProperty("observationIds") List<String> observationIds,
                            @JsonProperty("evidenceIds") List<String> evidenceIds,
                            @JsonProperty("expectedAmount") double expectedAmount,
                            @JsonProperty("expectedQuantity") int expectedQuantity,
                            @JsonProperty("provenance") String provenance,
                            @JsonProperty("proposedAt") long proposedAt,
                            @JsonProperty("recordedAt") long recordedAt,
                            @JsonProperty("expiresAt") long expiresAt,
                            @JsonProperty("diagnostic") String diagnostic) {
        this.proposalId = proposalId;
        this.transactionId = transactionId;
        this.idempotencyKey = idempotencyKey;
        this.targetEffectIds = List.copyOf(targetEffectIds == null ? List.of() : targetEffectIds);
        this.targetEffectTypes = List.copyOf(targetEffectTypes == null ? List.of() : targetEffectTypes);
        this.disposition = disposition;
        this.status = status;
        this.transactionFingerprint = transactionFingerprint;
        this.observationIds = List.copyOf(observationIds == null ? List.of() : observationIds);
        this.evidenceIds = List.copyOf(evidenceIds == null ? List.of() : evidenceIds);
        this.expectedAmount = expectedAmount;
        this.expectedQuantity = expectedQuantity;
        this.provenance = provenance;
        this.proposedAt = proposedAt;
        this.recordedAt = recordedAt;
        this.expiresAt = expiresAt;
        this.diagnostic = diagnostic;
    }

    public static RecoveryProposal create(String transactionId,
                                           String idempotencyKey,
                                           List<String> targetEffectIds,
                                           List<MarketTransactionEffectType> targetEffectTypes,
                                           RecoveryProposalDisposition disposition,
                                           RecoveryProposalStatus status,
                                           String transactionFingerprint,
                                           List<String> observationIds,
                                           List<String> evidenceIds,
                                           double expectedAmount,
                                           int expectedQuantity,
                                           String provenance,
                                           long proposedAt,
                                           long expiresAt,
                                           String diagnostic) {
        return new RecoveryProposal(UUID.randomUUID().toString(), transactionId, idempotencyKey,
                targetEffectIds, targetEffectTypes, disposition, status, transactionFingerprint,
                observationIds, evidenceIds, expectedAmount, expectedQuantity, provenance,
                proposedAt, System.currentTimeMillis(), expiresAt, diagnostic);
    }

    public String getProposalId() { return proposalId; }
    public String getTransactionId() { return transactionId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public List<String> getTargetEffectIds() { return targetEffectIds; }
    public List<MarketTransactionEffectType> getTargetEffectTypes() { return targetEffectTypes; }
    public RecoveryProposalDisposition getDisposition() { return disposition; }
    public RecoveryProposalStatus getStatus() { return status; }
    public String getTransactionFingerprint() { return transactionFingerprint; }
    public List<String> getObservationIds() { return observationIds; }
    public List<String> getEvidenceIds() { return evidenceIds; }
    public double getExpectedAmount() { return expectedAmount; }
    public int getExpectedQuantity() { return expectedQuantity; }
    public String getProvenance() { return provenance; }
    public long getProposedAt() { return proposedAt; }
    public long getRecordedAt() { return recordedAt; }
    public long getExpiresAt() { return expiresAt; }
    public String getDiagnostic() { return diagnostic; }
}

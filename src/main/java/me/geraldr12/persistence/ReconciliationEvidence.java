package me.geraldr12.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public final class ReconciliationEvidence {
    private final String evidenceId;
    private final String transactionId;
    private final String effectId;
    private final ReconciliationEvidenceType evidenceType;
    private final String externalReference;
    private final String operatorId;
    private final String operatorName;
    private final String statement;
    private final long createdAt;
    private final String idempotencyKey;

    @JsonCreator
    public ReconciliationEvidence(@JsonProperty("evidenceId") String evidenceId,
                                   @JsonProperty("transactionId") String transactionId,
                                   @JsonProperty("effectId") String effectId,
                                   @JsonProperty("evidenceType") ReconciliationEvidenceType evidenceType,
                                   @JsonProperty("externalReference") String externalReference,
                                   @JsonProperty("operatorId") String operatorId,
                                   @JsonProperty("operatorName") String operatorName,
                                   @JsonProperty("statement") String statement,
                                   @JsonProperty("createdAt") long createdAt,
                                   @JsonProperty("idempotencyKey") String idempotencyKey) {
        this.evidenceId = evidenceId;
        this.transactionId = transactionId;
        this.effectId = effectId;
        this.evidenceType = evidenceType;
        this.externalReference = externalReference;
        this.operatorId = operatorId;
        this.operatorName = operatorName;
        this.statement = statement;
        this.createdAt = createdAt;
        this.idempotencyKey = idempotencyKey;
    }

    public static ReconciliationEvidence create(String transactionId,
                                                 String effectId,
                                                 ReconciliationEvidenceType evidenceType,
                                                 String externalReference,
                                                 String operatorId,
                                                 String operatorName,
                                                 String statement,
                                                 long createdAt,
                                                 String idempotencyKey) {
        return new ReconciliationEvidence(UUID.randomUUID().toString(), transactionId, effectId,
                evidenceType, externalReference, operatorId, operatorName, statement,
                createdAt, idempotencyKey);
    }

    public String getEvidenceId() { return evidenceId; }
    public String getTransactionId() { return transactionId; }
    public String getEffectId() { return effectId; }
    public ReconciliationEvidenceType getEvidenceType() { return evidenceType; }
    public String getExternalReference() { return externalReference; }
    public String getOperatorId() { return operatorId; }
    public String getOperatorName() { return operatorName; }
    public String getStatement() { return statement; }
    public long getCreatedAt() { return createdAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
}

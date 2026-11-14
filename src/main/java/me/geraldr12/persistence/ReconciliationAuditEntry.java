package me.geraldr12.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public final class ReconciliationAuditEntry {
    private final String auditId;
    private final String transactionId;
    private final String evidenceId;
    private final String action;
    private final String operatorId;
    private final long timestamp;
    private final String requestId;

    @JsonCreator
    public ReconciliationAuditEntry(@JsonProperty("auditId") String auditId,
                                    @JsonProperty("transactionId") String transactionId,
                                    @JsonProperty("evidenceId") String evidenceId,
                                    @JsonProperty("action") String action,
                                    @JsonProperty("operatorId") String operatorId,
                                    @JsonProperty("timestamp") long timestamp,
                                    @JsonProperty("requestId") String requestId) {
        this.auditId = auditId;
        this.transactionId = transactionId;
        this.evidenceId = evidenceId;
        this.action = action;
        this.operatorId = operatorId;
        this.timestamp = timestamp;
        this.requestId = requestId;
    }

    public static ReconciliationAuditEntry evidenceRecorded(ReconciliationEvidence evidence) {
        return new ReconciliationAuditEntry(UUID.randomUUID().toString(), evidence.getTransactionId(),
                evidence.getEvidenceId(), "EVIDENCE_RECORDED", evidence.getOperatorId(),
                evidence.getCreatedAt(), evidence.getIdempotencyKey());
    }

    public String getAuditId() { return auditId; }
    public String getTransactionId() { return transactionId; }
    public String getEvidenceId() { return evidenceId; }
    public String getAction() { return action; }
    public String getOperatorId() { return operatorId; }
    public long getTimestamp() { return timestamp; }
    public String getRequestId() { return requestId; }
}

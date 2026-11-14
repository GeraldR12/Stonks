package me.geraldr12.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public final class VerificationObservation {
    private final String observationId;
    private final String transactionId;
    private final String effectId;
    private final MarketTransactionEffectType expectedEffectType;
    private final VerificationObservationSource source;
    private final VerificationObservationAssessment assessment;
    private final String externalReference;
    private final String provenance;
    private final long observationTimestamp;
    private final long recordedAt;
    private final String idempotencyKey;
    private final String diagnostic;

    @JsonCreator
    public VerificationObservation(@JsonProperty("observationId") String observationId,
                                    @JsonProperty("transactionId") String transactionId,
                                    @JsonProperty("effectId") String effectId,
                                    @JsonProperty("expectedEffectType") MarketTransactionEffectType expectedEffectType,
                                    @JsonProperty("source") VerificationObservationSource source,
                                    @JsonProperty("assessment") VerificationObservationAssessment assessment,
                                    @JsonProperty("externalReference") String externalReference,
                                    @JsonProperty("provenance") String provenance,
                                    @JsonProperty("observationTimestamp") long observationTimestamp,
                                    @JsonProperty("recordedAt") long recordedAt,
                                    @JsonProperty("idempotencyKey") String idempotencyKey,
                                    @JsonProperty("diagnostic") String diagnostic) {
        this.observationId = observationId;
        this.transactionId = transactionId;
        this.effectId = effectId;
        this.expectedEffectType = expectedEffectType;
        this.source = source;
        this.assessment = assessment;
        this.externalReference = externalReference;
        this.provenance = provenance;
        this.observationTimestamp = observationTimestamp;
        this.recordedAt = recordedAt;
        this.idempotencyKey = idempotencyKey;
        this.diagnostic = diagnostic;
    }

    public static VerificationObservation create(String transactionId,
                                                  String effectId,
                                                  MarketTransactionEffectType expectedEffectType,
                                                  VerificationObservationSource source,
                                                  VerificationObservationAssessment assessment,
                                                  String externalReference,
                                                  String provenance,
                                                  long observationTimestamp,
                                                  String idempotencyKey,
                                                  String diagnostic) {
        return new VerificationObservation(UUID.randomUUID().toString(), transactionId, effectId,
                expectedEffectType, source, assessment, externalReference, provenance,
                observationTimestamp, System.currentTimeMillis(), idempotencyKey, diagnostic);
    }

    public String getObservationId() { return observationId; }
    public String getTransactionId() { return transactionId; }
    public String getEffectId() { return effectId; }
    public MarketTransactionEffectType getExpectedEffectType() { return expectedEffectType; }
    public VerificationObservationSource getSource() { return source; }
    public VerificationObservationAssessment getAssessment() { return assessment; }
    public String getExternalReference() { return externalReference; }
    public String getProvenance() { return provenance; }
    public long getObservationTimestamp() { return observationTimestamp; }
    public long getRecordedAt() { return recordedAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getDiagnostic() { return diagnostic; }
}

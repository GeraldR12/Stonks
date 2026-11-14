package me.geraldr12.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.Data;
import me.geraldr12.data.entities.DataEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Data
public class MarketTransactionRecord implements DataEntity {
    public static final int LEGACY_SCHEMA_VERSION = 2;
    public static final int CURRENT_SCHEMA_VERSION = 3;

    private Integer schemaVersion;
    private long stateRevision;
    private String transactionId;
    private String operation; // BUY, SELL, CREATE, DELETE
    private String playerUuid;
    private Long companyId;
    private int quantity;
    private double value;
    private Long quoteCompanyId;
    private String quotePlayerUuid;
    private Integer quoteQuantity;
    private Double quoteUnitSharePrice;
    private Double quoteTotalValue;
    private Long quoteTimestamp;
    private long timestamp;
    private MarketTransactionStatus status;
    private String failureReason;
    private boolean legacyMigrated;
    private List<MarketTransactionEffect> effects = new ArrayList<>();

    @JsonProperty("reconciliationEvidence")
    @lombok.Getter(AccessLevel.NONE)
    @lombok.Setter(AccessLevel.NONE)
    private List<ReconciliationEvidence> reconciliationEvidence = new ArrayList<>();

    @JsonProperty("reconciliationAudit")
    @lombok.Getter(AccessLevel.NONE)
    @lombok.Setter(AccessLevel.NONE)
    private List<ReconciliationAuditEntry> reconciliationAudit = new ArrayList<>();

    @JsonProperty("verificationObservations")
    @lombok.Getter(AccessLevel.NONE)
    @lombok.Setter(AccessLevel.NONE)
    private List<VerificationObservation> verificationObservations = new ArrayList<>();

    @JsonProperty("recoveryProposals")
    @lombok.Getter(AccessLevel.NONE)
    @lombok.Setter(AccessLevel.NONE)
    private List<RecoveryProposal> recoveryProposals = new ArrayList<>();

    public MarketTransactionRecord() {
    }

    @JsonIgnore
    public boolean isCurrentSchema() {
        return Integer.valueOf(CURRENT_SCHEMA_VERSION).equals(schemaVersion);
    }

    @JsonIgnore
    public boolean isSupportedSchema() {
        return schemaVersion == null
                || Integer.valueOf(LEGACY_SCHEMA_VERSION).equals(schemaVersion)
                || isCurrentSchema();
    }

    @JsonIgnore
    public boolean isSchema3() {
        return isCurrentSchema();
    }

    public void ensureEffects() {
        if (effects == null) effects = new ArrayList<>();
    }

    public void ensureReconciliationData() {
        if (reconciliationEvidence == null) reconciliationEvidence = new ArrayList<>();
        if (reconciliationAudit == null) reconciliationAudit = new ArrayList<>();
    }

    public void ensureVerificationObservations() {
        if (verificationObservations == null) verificationObservations = new ArrayList<>();
    }

    public void ensureRecoveryProposals() {
        if (recoveryProposals == null) recoveryProposals = new ArrayList<>();
    }

    @JsonIgnore
    public List<RecoveryProposal> getRecoveryProposalsView() {
        return Collections.unmodifiableList(recoveryProposals == null
                ? List.of() : new ArrayList<>(recoveryProposals));
    }

    @JsonIgnore
    public List<VerificationObservation> getVerificationObservationsView() {
        return Collections.unmodifiableList(verificationObservations == null
                ? List.of() : new ArrayList<>(verificationObservations));
    }

    @JsonIgnore
    public List<ReconciliationEvidence> getReconciliationEvidenceView() {
        return Collections.unmodifiableList(reconciliationEvidence == null
                ? List.of() : new ArrayList<>(reconciliationEvidence));
    }

    @JsonIgnore
    public List<ReconciliationAuditEntry> getReconciliationAuditView() {
        return Collections.unmodifiableList(reconciliationAudit == null
                ? List.of() : new ArrayList<>(reconciliationAudit));
    }

    void appendVerificationObservation(VerificationObservation observation) {
        ensureVerificationObservations();
        verificationObservations.add(observation);
    }

    void appendRecoveryProposal(RecoveryProposal proposal) {
        ensureRecoveryProposals();
        recoveryProposals.add(proposal);
    }

    void removeRecoveryProposal(String proposalId) {
        ensureRecoveryProposals();
        recoveryProposals.removeIf(proposal -> proposalId.equals(proposal.getProposalId()));
    }

    void replaceRecoveryProposals(List<RecoveryProposal> proposals) {
        recoveryProposals = new ArrayList<>(proposals == null ? List.of() : proposals);
    }

    void removeVerificationObservation(String observationId) {
        ensureVerificationObservations();
        verificationObservations.removeIf(observation -> observationId.equals(observation.getObservationId()));
    }

    void replaceVerificationObservations(List<VerificationObservation> observations) {
        verificationObservations = new ArrayList<>(observations == null ? List.of() : observations);
    }

    void appendReconciliationEvidence(ReconciliationEvidence evidence) {
        ensureReconciliationData();
        reconciliationEvidence.add(evidence);
    }

    void appendReconciliationAudit(ReconciliationAuditEntry audit) {
        ensureReconciliationData();
        reconciliationAudit.add(audit);
    }

    void removeReconciliationEvidence(String evidenceId) {
        ensureReconciliationData();
        reconciliationEvidence.removeIf(evidence -> evidenceId.equals(evidence.getEvidenceId()));
    }

    void removeReconciliationAudit(String auditId) {
        ensureReconciliationData();
        reconciliationAudit.removeIf(audit -> auditId.equals(audit.getAuditId()));
    }

    void replaceReconciliationData(List<ReconciliationEvidence> evidence,
                                   List<ReconciliationAuditEntry> audit) {
        reconciliationEvidence = new ArrayList<>(evidence == null ? List.of() : evidence);
        reconciliationAudit = new ArrayList<>(audit == null ? List.of() : audit);
    }
}

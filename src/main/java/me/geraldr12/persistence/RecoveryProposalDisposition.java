package me.geraldr12.persistence;

public enum RecoveryProposalDisposition {
    PROPOSE_RECOVERY,
    NOT_VERIFIABLE,
    CONTRADICTORY,
    STALE_REJECTED,
    EXPIRED
}

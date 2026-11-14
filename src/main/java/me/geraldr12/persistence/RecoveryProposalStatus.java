package me.geraldr12.persistence;

public enum RecoveryProposalStatus {
    RECORDED,
    AWAITING_AUTHORITATIVE_PROOF,
    NOT_VERIFIABLE,
    CONTRADICTORY,
    STALE_REJECTED,
    EXPIRED,
    CONFLICTED
}

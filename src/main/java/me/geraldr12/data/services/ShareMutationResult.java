package me.geraldr12.data.services;

import java.util.UUID;

public final class ShareMutationResult {
    private static final int MAX_DIAGNOSTIC_LENGTH = 256;

    private final ShareMutationOutcome outcome;
    private final ShareMutationOperation operation;
    private final UUID playerId;
    private final Long companyId;
    private final long requestedQuantity;
    private final Long beforeQuantity;
    private final Long afterQuantity;
    private final Double purchaseUnitPrice;
    private final Double beforeAveragePrice;
    private final Double afterAveragePrice;
    private final String diagnostic;

    ShareMutationResult(
            ShareMutationOutcome outcome,
            ShareMutationOperation operation,
            UUID playerId,
            Long companyId,
            long requestedQuantity,
            Long beforeQuantity,
            Long afterQuantity,
            Double purchaseUnitPrice,
            Double beforeAveragePrice,
            Double afterAveragePrice,
            String diagnostic
    ) {
        this.outcome = require(outcome, "outcome");
        this.operation = require(operation, "operation");
        this.playerId = playerId;
        this.companyId = companyId;
        this.requestedQuantity = requestedQuantity;
        this.beforeQuantity = beforeQuantity;
        this.afterQuantity = afterQuantity;
        this.purchaseUnitPrice = purchaseUnitPrice;
        this.beforeAveragePrice = beforeAveragePrice;
        this.afterAveragePrice = afterAveragePrice;
        this.diagnostic = sanitize(diagnostic);
        validateConfirmedMetadata();
    }

    private void validateConfirmedMetadata() {
        if (outcome == ShareMutationOutcome.AMBIGUOUS && requestedQuantity <= 0) {
            throw new IllegalArgumentException("ambiguous mutation quantity must be positive");
        }
        if (outcome != ShareMutationOutcome.CONFIRMED) return;
        if (companyId == null || requestedQuantity <= 0 || beforeQuantity == null || afterQuantity == null) {
            throw new IllegalArgumentException("invalid confirmed mutation metadata");
        }
        if (operation == ShareMutationOperation.COMPANY_REMOVE || operation == ShareMutationOperation.COMPANY_RESTORE || operation == ShareMutationOperation.COMPANY_ADD) {
            if (playerId != null || purchaseUnitPrice != null || beforeAveragePrice != null
                    || afterAveragePrice != null || (operation == ShareMutationOperation.COMPANY_REMOVE || operation == ShareMutationOperation.COMPANY_RESTORE
                    ? (operation == ShareMutationOperation.COMPANY_REMOVE
                        ? (beforeQuantity < afterQuantity || requestedQuantity != beforeQuantity - afterQuantity)
                        : (afterQuantity < beforeQuantity || requestedQuantity != afterQuantity - beforeQuantity))
                    : (afterQuantity < beforeQuantity || requestedQuantity != afterQuantity - beforeQuantity))) {
                throw new IllegalArgumentException("invalid confirmed company metadata");
            }
            return;
        }
        if (operation == ShareMutationOperation.COMPANY_ADD_RESTORE) {
            if (playerId != null || purchaseUnitPrice != null || beforeAveragePrice != null || afterAveragePrice != null) {
                throw new IllegalArgumentException("invalid confirmed company add restore metadata");
            }
            if (beforeQuantity <= afterQuantity || requestedQuantity != beforeQuantity - afterQuantity) {
                throw new IllegalArgumentException("invalid confirmed company add restore metadata");
            }
            return;
        }
        if (operation == ShareMutationOperation.PLAYER_REMOVE) {
            if (playerId == null || purchaseUnitPrice != null || afterAveragePrice != null) {
                throw new IllegalArgumentException("invalid confirmed player remove metadata");
            }
            if (beforeAveragePrice == null || !Double.isFinite(beforeAveragePrice) || beforeAveragePrice <= 0) {
                throw new IllegalArgumentException("invalid confirmed player remove metadata");
            }
            if (beforeQuantity <= afterQuantity || requestedQuantity != beforeQuantity - afterQuantity) {
                throw new IllegalArgumentException("invalid confirmed player remove metadata");
            }
            return;
        }
        if (operation == ShareMutationOperation.PLAYER_REMOVE_RESTORE) {
            if (playerId == null || purchaseUnitPrice != null || afterAveragePrice != null) {
                throw new IllegalArgumentException("invalid confirmed player remove restore metadata");
            }
            if (beforeAveragePrice == null || !Double.isFinite(beforeAveragePrice) || beforeAveragePrice <= 0) {
                throw new IllegalArgumentException("invalid confirmed player remove restore metadata");
            }
            if (afterQuantity <= beforeQuantity || requestedQuantity != afterQuantity - beforeQuantity) {
                throw new IllegalArgumentException("invalid confirmed player remove restore metadata");
            }
            return;
        }
        if (operation != ShareMutationOperation.PLAYER_ADD && operation != ShareMutationOperation.PLAYER_RESTORE
                || playerId == null || purchaseUnitPrice == null
                || !Double.isFinite(purchaseUnitPrice) || purchaseUnitPrice <= 0) {
            throw new IllegalArgumentException("invalid confirmed player metadata");
        }
        if (operation == ShareMutationOperation.PLAYER_RESTORE) {
            if (beforeAveragePrice == null || !Double.isFinite(beforeAveragePrice) || beforeAveragePrice <= 0) {
                throw new IllegalArgumentException("invalid confirmed player restoration metadata");
            }
            if (beforeQuantity <= afterQuantity || requestedQuantity != beforeQuantity - afterQuantity
                    || (afterQuantity == 0 ? afterAveragePrice != null
                    : afterAveragePrice == null || !Double.isFinite(afterAveragePrice) || afterAveragePrice <= 0)) {
                throw new IllegalArgumentException("invalid confirmed player restoration metadata");
            }
            return;
        }
        if (beforeQuantity == 0) {
            if (beforeAveragePrice != null || afterQuantity != requestedQuantity
                    || Double.compare(afterAveragePrice, purchaseUnitPrice) != 0) {
                throw new IllegalArgumentException("invalid confirmed absent-player metadata");
            }
        } else if (beforeAveragePrice == null || !Double.isFinite(beforeAveragePrice) || beforeAveragePrice <= 0) {
            throw new IllegalArgumentException("invalid confirmed existing-player metadata");
        } else {
            long added = afterQuantity - beforeQuantity;
            if (added != requestedQuantity || Double.compare(
                    (beforeQuantity * beforeAveragePrice + added * purchaseUnitPrice) / afterQuantity,
                    afterAveragePrice) != 0) {
                throw new IllegalArgumentException("invalid confirmed average metadata");
            }
        }
    }

    static ShareMutationResult rejected(ShareMutationOperation operation, UUID playerId, Long companyId,
                                        long requestedQuantity, String diagnostic) {
        return new ShareMutationResult(ShareMutationOutcome.DEFINITE_REJECTED, operation, playerId, companyId,
                requestedQuantity, null, null, null, null, null, diagnostic);
    }

    static ShareMutationResult ambiguous(ShareMutationOperation operation, UUID playerId, Long companyId,
                                         long requestedQuantity, Long beforeQuantity, Long afterQuantity,
                                         Double purchaseUnitPrice, Double beforeAveragePrice,
                                         Double afterAveragePrice, String diagnostic) {
        return new ShareMutationResult(ShareMutationOutcome.AMBIGUOUS, operation, playerId, companyId,
                requestedQuantity, beforeQuantity, afterQuantity, purchaseUnitPrice,
                beforeAveragePrice, afterAveragePrice, diagnostic);
    }

    static ShareMutationResult confirmed(ShareMutationOperation operation, UUID playerId, Long companyId,
                                         long requestedQuantity, Long beforeQuantity, Long afterQuantity,
                                         Double purchaseUnitPrice, Double beforeAveragePrice,
                                         Double afterAveragePrice) {
        return new ShareMutationResult(ShareMutationOutcome.CONFIRMED, operation, playerId, companyId,
                requestedQuantity, beforeQuantity, afterQuantity, purchaseUnitPrice,
                beforeAveragePrice, afterAveragePrice, "confirmed");
    }

    public ShareMutationOutcome getOutcome() { return outcome; }
    public ShareMutationOperation getOperation() { return operation; }
    public UUID getPlayerId() { return playerId; }
    public Long getCompanyId() { return companyId; }
    public long getRequestedQuantity() { return requestedQuantity; }
    public Long getBeforeQuantity() { return beforeQuantity; }
    public Long getAfterQuantity() { return afterQuantity; }
    public Double getPurchaseUnitPrice() { return purchaseUnitPrice; }
    public Double getBeforeAveragePrice() { return beforeAveragePrice; }
    public Double getAfterAveragePrice() { return afterAveragePrice; }
    public String getDiagnostic() { return diagnostic; }

    ConfirmedCompanyRemovalEvidence companyRemovalEvidence() {
        if (outcome != ShareMutationOutcome.CONFIRMED || operation != ShareMutationOperation.COMPANY_REMOVE
                || companyId == null || beforeQuantity == null || afterQuantity == null) {
            throw new IllegalStateException("company removal evidence is unavailable");
        }
        return new ConfirmedCompanyRemovalEvidence(companyId, requestedQuantity, beforeQuantity, afterQuantity);
    }

    ConfirmedPlayerAddEvidence playerAddEvidence() {
        if (outcome != ShareMutationOutcome.CONFIRMED || operation != ShareMutationOperation.PLAYER_ADD
                || playerId == null || companyId == null || beforeQuantity == null || afterQuantity == null
                || afterAveragePrice == null || purchaseUnitPrice == null) {
            throw new IllegalStateException("player add evidence is unavailable");
        }
        return new ConfirmedPlayerAddEvidence(playerId, companyId, beforeQuantity, afterQuantity,
                beforeAveragePrice, afterAveragePrice, purchaseUnitPrice);
    }

    ConfirmedCompanyAddEvidence companyAddEvidence() {
        if (outcome != ShareMutationOutcome.CONFIRMED || operation != ShareMutationOperation.COMPANY_ADD
                || companyId == null || beforeQuantity == null || afterQuantity == null) {
            throw new IllegalStateException("company add evidence is unavailable");
        }
        return new ConfirmedCompanyAddEvidence(companyId, requestedQuantity, beforeQuantity, afterQuantity);
    }

    ConfirmedPlayerRemovalEvidence playerRemovalEvidence() {
        if (outcome != ShareMutationOutcome.CONFIRMED || operation != ShareMutationOperation.PLAYER_REMOVE
                || playerId == null || companyId == null || beforeQuantity == null || afterQuantity == null
                || beforeAveragePrice == null) {
            throw new IllegalStateException("player removal evidence is unavailable");
        }
        return new ConfirmedPlayerRemovalEvidence(playerId, companyId, beforeQuantity, afterQuantity, beforeAveragePrice);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return "";
        String singleLine = value.replaceAll("[\\r\\n\\t]", " ");
        return singleLine.length() <= MAX_DIAGNOSTIC_LENGTH
                ? singleLine : singleLine.substring(0, MAX_DIAGNOSTIC_LENGTH);
    }
}

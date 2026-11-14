package me.geraldr12.data.services;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ShareMutationResultTest {
    @Test
    void confirmedCompanyRemovalAndEvidenceValidateArithmetic() {
        ShareMutationResult result = ShareMutationResult.confirmed(
                ShareMutationOperation.COMPANY_REMOVE, null, 7L,
                5, 100L, 95L, null, null, null);
        assertEquals(ShareMutationOutcome.CONFIRMED, result.getOutcome());
        assertEquals(5, result.companyRemovalEvidence().requestedQuantity());
    }

    @Test
    void confirmedPlayerAddSupportsAbsentHolding() {
        ShareMutationResult result = ShareMutationResult.confirmed(
                ShareMutationOperation.PLAYER_ADD, UUID.randomUUID(), 7L,
                5, 0L, 5L, 200.0, null, 200.0);
        assertNull(result.getBeforeAveragePrice());
        assertNull(result.playerAddEvidence().beforeAveragePrice());
    }

    @Test
    void confirmedPlayerAddValidatesExistingWeightedAverage() {
        ShareMutationResult result = ShareMutationResult.confirmed(
                ShareMutationOperation.PLAYER_ADD, UUID.randomUUID(), 7L,
                5, 10L, 15L, 200.0, 100.0, 133.33333333333334);
        assertEquals(133.33333333333334, result.getAfterAveragePrice());
    }

    @Test
    void invalidConfirmedMetadataIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ShareMutationResult.confirmed(
                ShareMutationOperation.PLAYER_ADD, UUID.randomUUID(), 7L,
                4, 0L, 5L, 200.0, 0.0, 200.0));
        assertThrows(IllegalArgumentException.class, () -> ShareMutationResult.confirmed(
                ShareMutationOperation.COMPANY_REMOVE, UUID.randomUUID(), 7L,
                5, 100L, 95L, null, null, null));
    }

    @Test
    void preservesInvalidQuantityAndSanitizesDiagnostic() {
        String diagnostic = "bad\n" + "x".repeat(400);
        ShareMutationResult result = ShareMutationResult.rejected(
                ShareMutationOperation.PLAYER_ADD, UUID.randomUUID(), 7L, 0, diagnostic);
        assertEquals(0, result.getRequestedQuantity());
        assertFalse(result.getDiagnostic().contains("\n"));
        assertTrue(result.getDiagnostic().length() <= 256);
        assertThrows(IllegalArgumentException.class, () -> ShareMutationResult.ambiguous(
                ShareMutationOperation.PLAYER_ADD, UUID.randomUUID(), 7L, 0,
                0L, 0L, 200.0, null, 200.0, "invalid"));
    }

    @Test
    void confirmedPlayerRemoveValidatesExactArithmetic() {
        UUID playerId = UUID.randomUUID();
        ShareMutationResult result = ShareMutationResult.confirmed(
                ShareMutationOperation.PLAYER_REMOVE, playerId, 7L,
                4, 10L, 6L, null, 100.0, null);
        assertEquals(ShareMutationOutcome.CONFIRMED, result.getOutcome());
        assertEquals(4, result.playerRemovalEvidence().beforeQuantity() - result.playerRemovalEvidence().afterQuantity());
    }

    @Test
    void confirmedPlayerRemoveFullExitIsAccepted() {
        UUID playerId = UUID.randomUUID();
        ShareMutationResult result = ShareMutationResult.confirmed(
                ShareMutationOperation.PLAYER_REMOVE, playerId, 7L,
                10, 10L, 0L, null, 100.0, null);
        assertEquals(ShareMutationOutcome.CONFIRMED, result.getOutcome());
        assertEquals(0L, result.playerRemovalEvidence().afterQuantity());
    }

    @Test
    void confirmedPlayerRemoveRejectsInvalidMetadata() {
        UUID playerId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> ShareMutationResult.confirmed(
                ShareMutationOperation.PLAYER_REMOVE, playerId, 7L,
                4, 10L, 6L, 200.0, 100.0, null)); // purchase price must be null
        assertThrows(IllegalArgumentException.class, () -> ShareMutationResult.confirmed(
                ShareMutationOperation.PLAYER_REMOVE, playerId, 7L,
                4, 10L, 6L, null, 100.0, 100.0)); // after price must be null
        assertThrows(IllegalArgumentException.class, () -> ShareMutationResult.confirmed(
                ShareMutationOperation.PLAYER_REMOVE, playerId, 7L,
                4, 10L, 5L, null, 100.0, null)); // invalid delta
        assertThrows(IllegalArgumentException.class, () -> ShareMutationResult.confirmed(
                ShareMutationOperation.PLAYER_REMOVE, null, 7L,
                4, 10L, 6L, null, 100.0, null)); // missing player ID
    }

    @Test
    void confirmedCompanyAddValidatesArithmetic() {
        ShareMutationResult result = ShareMutationResult.confirmed(
                ShareMutationOperation.COMPANY_ADD, null, 7L,
                5, 95L, 100L, null, null, null);
        assertEquals(ShareMutationOutcome.CONFIRMED, result.getOutcome());
        assertEquals(5, result.companyAddEvidence().requestedQuantity());
        assertEquals(95L, result.companyAddEvidence().beforeQuantity());
        assertEquals(100L, result.companyAddEvidence().afterQuantity());
    }

    @Test
    void confirmedCompanyAddRejectsInvalidMetadata() {
        assertThrows(IllegalArgumentException.class, () -> ShareMutationResult.confirmed(
                ShareMutationOperation.COMPANY_ADD, UUID.randomUUID(), 7L, // player ID present
                5, 95L, 100L, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> ShareMutationResult.confirmed(
                ShareMutationOperation.COMPANY_ADD, null, 7L,
                5, 95L, 99L, null, null, null)); // invalid delta
    }

    @Test
    void confirmedCompanyAddRestoreValidatesArithmetic() {
        ShareMutationResult result = ShareMutationResult.confirmed(
                ShareMutationOperation.COMPANY_ADD_RESTORE, null, 7L,
                5, 100L, 95L, null, null, null);
        assertEquals(ShareMutationOutcome.CONFIRMED, result.getOutcome());
    }

    @Test
    void confirmedPlayerRemoveRestoreValidatesArithmetic() {
        ShareMutationResult result = ShareMutationResult.confirmed(
                ShareMutationOperation.PLAYER_REMOVE_RESTORE, UUID.randomUUID(), 7L,
                4, 6L, 10L, null, 100.0, null);
        assertEquals(ShareMutationOutcome.CONFIRMED, result.getOutcome());
    }
}

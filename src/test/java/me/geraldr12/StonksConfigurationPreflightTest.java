package me.geraldr12;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StonksConfigurationPreflightTest {
    @Test
    void acceptsOnlyPositiveInterestIntervalsWithinSchedulerRange() {
        assertFalse(Stonks.isValidInterestInterval(0));
        assertFalse(Stonks.isValidInterestInterval(-1));
        assertTrue(Stonks.isValidInterestInterval(1));
        assertTrue(Stonks.isValidInterestInterval(Integer.MAX_VALUE / 1200));
        assertFalse(Stonks.isValidInterestInterval(Integer.MAX_VALUE / 1200 + 1));
    }

    @Test
    void postRecoveryValidationRequiresFreshValidAssessment() {
        java.util.concurrent.atomic.AtomicInteger inspections =
                new java.util.concurrent.atomic.AtomicInteger();
        assertTrue(Stonks.runPostRecoveryValidation(
                new me.geraldr12.persistence.JournalIntegrityResult(
                        me.geraldr12.persistence.JournalIntegrityAssessment.VALID,
                        java.util.List.of(), 0, 0),
                () -> {
                    inspections.incrementAndGet();
                    return new me.geraldr12.persistence.JournalIntegrityResult(
                            me.geraldr12.persistence.JournalIntegrityAssessment.VALID,
                            java.util.List.of(), 0, 0);
                }));
        assertEquals(1, inspections.get());
        assertFalse(Stonks.runPostRecoveryValidation(
                new me.geraldr12.persistence.JournalIntegrityResult(
                        me.geraldr12.persistence.JournalIntegrityAssessment.VALID,
                        java.util.List.of(), 0, 0),
                () -> new me.geraldr12.persistence.JournalIntegrityResult(
                        me.geraldr12.persistence.JournalIntegrityAssessment.INVALID,
                        java.util.List.of(), 0, 0)));
        assertFalse(Stonks.runPostRecoveryValidation(null, () -> {
            throw new AssertionError("inspection must be suppressed");
        }));
    }

    @Test
    void onlyValidJournalAssessmentInvokesRecoveryExactlyOnce() {
        me.geraldr12.persistence.JournalIntegrityAssessment[] assessments =
                me.geraldr12.persistence.JournalIntegrityAssessment.values();
        for (me.geraldr12.persistence.JournalIntegrityAssessment assessment : assessments) {
            java.util.concurrent.atomic.AtomicInteger calls =
                    new java.util.concurrent.atomic.AtomicInteger();
            boolean recovered = Stonks.runRecoveryAfterValidation(
                    new me.geraldr12.persistence.JournalIntegrityResult(
                            assessment, java.util.List.of(), 0, 0),
                    () -> calls.incrementAndGet() == 1);
            if (assessment == me.geraldr12.persistence.JournalIntegrityAssessment.VALID) {
                assertTrue(recovered);
                assertEquals(1, calls.get());
            } else {
                assertFalse(recovered);
                assertEquals(0, calls.get());
            }
        }

        java.util.concurrent.atomic.AtomicInteger nullCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        assertFalse(Stonks.runRecoveryAfterValidation(null,
                () -> nullCalls.incrementAndGet() == 1));
        assertEquals(0, nullCalls.get());
    }
}

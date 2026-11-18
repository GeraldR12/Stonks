package me.geraldr12.data.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CompanyFundsServiceTest {
    @TempDir Path temp;

    private Path ledger() {
        return temp.resolve("funds").resolve("ledger.yml");
    }

    private CompanyFundsService funds() {
        return new CompanyFundsService(() -> true, ledger());
    }

    @Test
    void creditAddsToTheCompanyAndSurvivesRestart() {
        CompanyFundsService funds = funds();
        assertTrue(funds.credit(7, 120.0, "tx-1"));
        assertTrue(funds.credit(7, 30.5, "tx-2"));
        assertEquals(150.5, funds.balance(7), 1e-9);
        assertEquals(0.0, funds.balance(8));
        assertEquals(150.5, funds().balance(7), 1e-9);
    }

    @Test
    void theSameTransactionNeverCreditsTwice() {
        CompanyFundsService funds = funds();
        assertTrue(funds.credit(7, 100.0, "tx-1"));
        assertTrue(funds.credit(7, 100.0, "tx-1"));
        assertEquals(100.0, funds.balance(7), 1e-9);
        assertTrue(funds().credit(7, 100.0, "tx-1"));
        assertEquals(100.0, funds().balance(7), 1e-9);
    }

    @Test
    void rejectsInvalidCredits() {
        CompanyFundsService funds = funds();
        assertFalse(funds.credit(7, 0.0, "a"));
        assertFalse(funds.credit(7, -1.0, "b"));
        assertFalse(funds.credit(7, Double.NaN, "c"));
        assertFalse(funds.credit(-1, 5.0, "d"));
        assertFalse(funds.credit(7, 5.0, null));
        assertEquals(0.0, funds.balance(7));
    }

    @Test
    void reserveNeverGoesBelowZero() {
        CompanyFundsService funds = funds();
        funds.credit(7, 50.0, "tx-1");
        assertFalse(funds.reserve(7, 50.01, "sell-1", "SELL", null));
        assertEquals(50.0, funds.balance(7), 1e-9);
        assertFalse(funds.hasPending(7));
        assertTrue(funds.reserve(7, 50.0, "sell-2", "SELL", null));
        assertEquals(0.0, funds.balance(7), 1e-9);
        assertTrue(funds.hasPending(7));
    }

    @Test
    void settledReservationIsGoneAndCounted() {
        CompanyFundsService funds = funds();
        funds.credit(7, 50.0, "tx-1");
        funds.reserve(7, 20.0, "sell-1", "SELL", "player");
        assertTrue(funds.settle("sell-1"));
        assertFalse(funds.hasPending(7));
        assertEquals(30.0, funds.balance(7), 1e-9);
        assertEquals(20.0, funds.paidOut(), 1e-9);
        assertFalse(funds.settle("sell-1"));
    }

    @Test
    void releasedReservationReturnsToTheCompany() {
        CompanyFundsService funds = funds();
        funds.credit(7, 50.0, "tx-1");
        funds.reserve(7, 20.0, "sell-1", "SELL", null);
        assertTrue(funds.release("sell-1"));
        assertEquals(50.0, funds.balance(7), 1e-9);
        assertFalse(funds.hasPending(7));
        assertFalse(funds.release("sell-1"));
    }

    @Test
    void pendingReservationSurvivesRestartAndBlocksTheCompany() {
        CompanyFundsService funds = funds();
        funds.credit(7, 50.0, "tx-1");
        funds.reserve(7, 20.0, "sell-1", "SELL", "p");
        CompanyFundsService reloaded = funds();
        assertTrue(reloaded.hasPending(7));
        assertFalse(reloaded.hasPending(8));
        assertEquals(30.0, reloaded.balance(7), 1e-9);
        assertEquals(1, reloaded.pending().size());
        CompanyFundsService.Pending pending = reloaded.pending().get(0);
        assertEquals("sell-1", pending.id());
        assertEquals(7, pending.companyId());
        assertEquals("SELL", pending.kind());
        assertEquals(20.0, pending.amount(), 1e-9);
        assertTrue(reloaded.release("sell-1"));
        assertEquals(50.0, funds().balance(7), 1e-9);
    }

    @Test
    void drainEmptiesTheCompanyIntoAPendingOperation() {
        CompanyFundsService funds = funds();
        funds.credit(7, 42.0, "tx-1");
        assertEquals(42.0, funds.drain(7, "close-1", "TREASURY"), 1e-9);
        assertEquals(0.0, funds.balance(7));
        assertTrue(funds.hasPending(7));
        assertEquals(0.0, funds.drain(8, "close-2", "TREASURY"));
        assertFalse(funds.hasPending(8));
    }

    @Test
    void disabledServiceDoesNothing() {
        CompanyFundsService funds = new CompanyFundsService(() -> false, ledger());
        assertFalse(funds.enabled());
        assertFalse(funds.credit(7, 10.0, "tx"));
        assertFalse(funds.reserve(7, 1.0, "s", "SELL", null));
        assertFalse(Files.exists(ledger()));
    }

    @Test
    void corruptLedgerPausesEverything() throws Exception {
        Files.createDirectories(ledger().getParent());
        Files.writeString(ledger(), "balances: [unclosed");
        CompanyFundsService funds = funds();
        assertTrue(funds.needsReview());
        assertTrue(funds.hasPending(1));
        assertFalse(funds.credit(1, 10.0, "tx"));
        assertFalse(funds.reserve(1, 1.0, "s", "SELL", null));
        assertEquals("balances: [unclosed", Files.readString(ledger()));
    }

    @Test
    void addsInDecimalWithoutFloatNoise() {
        CompanyFundsService funds = funds();
        funds.credit(1, 0.1, "a");
        funds.credit(1, 0.2, "b");
        assertEquals(0.3, funds.balance(1));
    }

    @Test
    void removeDropsAnEmptyCompanyOnly() {
        CompanyFundsService funds = funds();
        funds.credit(1, 5.0, "a");
        assertFalse(funds.remove(1));
        funds.reserve(1, 5.0, "x", "DELETE", null);
        funds.settle("x");
        assertTrue(funds.remove(1));
        assertEquals(0.0, funds.balance(1));
    }

    @Test
    void totalHeldCountsBalancesAndPending() {
        CompanyFundsService funds = funds();
        funds.credit(1, 5.0, "a");
        funds.credit(2, 7.0, "b");
        funds.reserve(2, 3.0, "x", "SELL", null);
        assertEquals(12.0, funds.totalHeld(), 1e-9);
    }
}

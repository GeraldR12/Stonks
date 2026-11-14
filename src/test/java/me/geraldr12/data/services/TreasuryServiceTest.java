package me.geraldr12.data.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TreasuryServiceTest {
    @TempDir Path temp;

    static final class FakeGateway implements TreasuryService.Gateway {
        String account = "Server_Account";
        boolean accept = true;
        int digits = 0;
        final List<Double> deposits = new ArrayList<>();

        @Override public String account() { return account; }
        @Override public boolean deposit(String name, double amount) {
            if (!accept) return false;
            deposits.add(amount);
            return true;
        }
        @Override public int fractionalDigits() { return digits; }
    }

    private Path ledger() {
        return temp.resolve("treasury").resolve("ledger.yml");
    }

    @Test
    void depositsFeeRoundedDown() {
        FakeGateway gateway = new FakeGateway();
        TreasuryService treasury = new TreasuryService(gateway, ledger());
        treasury.credit(3.99);
        assertEquals(List.of(3.0), gateway.deposits);
        assertEquals(3.0, treasury.credited());
        assertEquals(0.99, treasury.owed(), 1e-6); // kept for the next fee, never lost
    }

    @Test
    void fractionalRemaindersAccumulateInsteadOfVanishing() {
        FakeGateway gateway = new FakeGateway();
        TreasuryService treasury = new TreasuryService(gateway, ledger());
        treasury.credit(0.6);
        treasury.credit(0.6);
        assertEquals(List.of(1.0), gateway.deposits);
        assertEquals(0.2, treasury.owed(), 1e-6);
    }

    @Test
    void neverDepositsMoreThanWasPaid() {
        FakeGateway gateway = new FakeGateway();
        gateway.digits = 2;
        TreasuryService treasury = new TreasuryService(gateway, ledger());
        double paid = 0;
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 500; i++) {
            double fee = random.nextDouble() * 10;
            paid += fee;
            treasury.credit(fee);
        }
        double deposited = gateway.deposits.stream().mapToDouble(Double::doubleValue).sum();
        assertTrue(deposited <= paid + 1e-6, deposited + " > " + paid);
        assertEquals(paid, deposited + treasury.owed(), 0.01 * 500);
    }

    @Test
    void floatNoiseNeverCostsAWholeUnit() {
        FakeGateway gateway = new FakeGateway();
        gateway.accept = false;
        TreasuryService treasury = new TreasuryService(gateway, ledger());
        treasury.credit(40);
        treasury.credit(1.2000000000000002);
        treasury.credit(0.7999999999999972);
        gateway.accept = true;
        treasury.flush();
        assertEquals(List.of(42.0), gateway.deposits);
    }

    @Test
    void offWhenNoAccount() {
        FakeGateway gateway = new FakeGateway();
        gateway.account = " ";
        TreasuryService treasury = new TreasuryService(gateway, ledger());
        treasury.credit(10);
        assertTrue(gateway.deposits.isEmpty());
        assertFalse(treasury.enabled());
        assertEquals(0.0, treasury.owed());
    }

    @Test
    void ignoresZeroNegativeAndNonFinite() {
        FakeGateway gateway = new FakeGateway();
        TreasuryService treasury = new TreasuryService(gateway, ledger());
        treasury.credit(0);
        treasury.credit(-5);
        treasury.credit(Double.NaN);
        treasury.credit(Double.POSITIVE_INFINITY);
        assertTrue(gateway.deposits.isEmpty());
        assertEquals(0.0, treasury.owed());
    }

    @Test
    void failedDepositIsOwedAndRetriedAfterRestart() {
        FakeGateway gateway = new FakeGateway();
        gateway.accept = false;
        TreasuryService first = new TreasuryService(gateway, ledger());
        first.credit(5);
        first.credit(2);
        assertEquals(7.0, first.owed());
        assertTrue(gateway.deposits.isEmpty());

        gateway.accept = true;
        TreasuryService second = new TreasuryService(gateway, ledger());
        assertEquals(7.0, second.owed());
        second.flush();
        assertEquals(List.of(7.0), gateway.deposits);
        assertEquals(0.0, second.owed());
        assertEquals(7.0, new TreasuryService(gateway, ledger()).credited());
    }

    @Test
    void gatewayExceptionKeepsFeeOwed() {
        TreasuryService.Gateway exploding = new TreasuryService.Gateway() {
            @Override public String account() { return "Server_Account"; }
            @Override public boolean deposit(String name, double amount) { throw new IllegalStateException("db down"); }
            @Override public int fractionalDigits() { return 0; }
        };
        TreasuryService treasury = new TreasuryService(exploding, ledger());
        assertDoesNotThrow(() -> treasury.credit(4));
        assertEquals(4.0, treasury.owed());
        assertDoesNotThrow(() -> TreasuryService.credit(null, 4));
    }
}

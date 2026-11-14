package me.geraldr12.data.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SellTransactionResultValueTest {
    @Test
    void fourArgumentConstructorDefaultsToNaN() {
        SellTransactionResult result = new SellTransactionResult(
                SellTransactionResult.Status.SUCCESS, 0, 2, "tx");

        assertTrue(Double.isNaN(result.totalValue()));
        assertIdentity(result);
    }

    @Test
    void fiveArgumentConstructorPreservesValue() {
        SellTransactionResult result = new SellTransactionResult(
                SellTransactionResult.Status.SUCCESS, 0, 2, "tx", 146.0);

        assertEquals(146.0, result.totalValue());
        assertIdentity(result);
    }

    @Test
    void fourArgumentFactoryDefaultsToNaN() {
        SellTransactionResult result = SellTransactionResult.of(
                SellTransactionResult.Status.SUCCESS, 0, 2, "tx");

        assertTrue(Double.isNaN(result.totalValue()));
        assertIdentity(result);
    }

    @Test
    void fiveArgumentFactoryPreservesValue() {
        SellTransactionResult result = SellTransactionResult.of(
                SellTransactionResult.Status.SUCCESS, 0, 2, "tx", 146.0);

        assertEquals(146.0, result.totalValue());
        assertIdentity(result);
    }

    private void assertIdentity(SellTransactionResult result) {
        assertEquals(SellTransactionResult.Status.SUCCESS, result.status());
        assertEquals(0, result.companyId());
        assertEquals(2, result.quantity());
        assertEquals("tx", result.transactionId());
    }
}

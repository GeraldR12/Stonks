package me.geraldr12.commands;

import me.geraldr12.persistence.MarketTransactionStatus;
import me.geraldr12.utils.MarketTransactionLocks;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CreateDeleteRoundingTest {
    private static final double VALUATION = 190.16877954013523;
    private static final double CREATION_TAX = 7.25 * 13 * 1.1;

    @TempDir Path temp;

    @AfterEach
    void tearDown() {
        try {
            assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
        } finally {
            MarketTransactionLocks.revoke();
        }
    }

    @TestFactory
    Stream<DynamicTest> deleteRoundsToCurrencyPrecision() {
        return Stream.of(0, 2, -1).map(digits -> DynamicTest.dynamicTest("DELETE digits=" + digits, () -> {
            FinancialCommandFixture fixture = fixture(false, "delete-" + digits, VALUATION);
            double expected = digits == 0 ? 190.0 : digits == 2 ? 190.16 : VALUATION;
            when(fixture.economy.fractionalDigits()).thenReturn(digits);
            when(fixture.economy.depositPlayer(eq(fixture.player), anyDouble()))
                    .thenReturn(success(expected));

            command(fixture);

            verify(fixture.economy).depositPlayer(fixture.player, expected);
            assertEquals(expected, fixture.record().getValue());
            assertEquals(MarketTransactionStatus.COMPLETED, fixture.record().getStatus());
            verify(fixture.player).sendMessage(MessageFormat.format("Sold {0} for {1}", 20, expected));
        }));
    }

    @TestFactory
    Stream<DynamicTest> createRoundsToCurrencyPrecision() {
        return Stream.of(0, 2, -1).map(digits -> DynamicTest.dynamicTest("CREATE digits=" + digits, () -> {
            FinancialCommandFixture fixture = fixture(true, "create-" + digits, CREATION_TAX);
            double expected = digits == 0 ? 103.0 : digits == 2 ? 103.67 : CREATION_TAX;
            when(fixture.economy.fractionalDigits()).thenReturn(digits);
            when(fixture.economy.has(eq(fixture.player), anyDouble())).thenReturn(true);
            when(fixture.economy.withdrawPlayer(eq(fixture.player), anyDouble()))
                    .thenReturn(success(expected));

            command(fixture);

            verify(fixture.economy).has(fixture.player, expected);
            verify(fixture.economy).withdrawPlayer(fixture.player, expected);
            assertEquals(expected, fixture.record().getValue());
            assertEquals(MarketTransactionStatus.COMPLETED, fixture.record().getStatus());
            verify(fixture.player).sendMessage("Created Acme");
        }));
    }

    @TestFactory
    Stream<DynamicTest> invalidPrecisionOrZeroRoundedValueHasNoFinancialEffects() {
        return Stream.of(true, false).flatMap(create -> Stream.of("invalid", "exception", "zero")
                .map(reason -> DynamicTest.dynamicTest((create ? "CREATE " : "DELETE ") + reason, () -> {
                    FinancialCommandFixture fixture = fixture(create, create + "-" + reason,
                            reason.equals("zero") ? 0.5 : create ? CREATION_TAX : VALUATION);
                    if (reason.equals("exception")) {
                        when(fixture.economy.fractionalDigits()).thenThrow(new IllegalStateException("precision"));
                    } else {
                        when(fixture.economy.fractionalDigits()).thenReturn(reason.equals("invalid") ? -2 : 0);
                    }

                    assertTrue(command(fixture));

                    verify(fixture.economy, never()).has(any(OfflinePlayer.class), anyDouble());
                    verify(fixture.economy, never()).withdrawPlayer(any(OfflinePlayer.class), anyDouble());
                    verify(fixture.economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
                    verify(fixture.journal, never()).prepare(any());
                    assertTrue(fixture.journal.getAllPendingOrReconciliationRecords().isEmpty());
                    verify(fixture.companies, never()).reserveCompanyId(any());
                    verify(fixture.companies, never()).saveReservedCompany(any());
                    verify(fixture.companies, never()).deleteCompany(anyLong());
                    verify(fixture.players, never()).addSharesToPlayer(any(), any(), anyInt());
                    verify(fixture.players, never()).removeSharesFromPlayer(any(), anyLong(), anyInt());
                    verify(fixture.player).sendMessage(create
                            ? "Unable to calculate a valid creation tax."
                            : "Unable to calculate a valid company valuation.");
                })));
    }

    @Test
    void insufficientFundsMessageUsesRoundedTax() {
        FinancialCommandFixture fixture = fixture(true, "insufficient", CREATION_TAX);
        when(fixture.plugin.getMessages().getInsufficientMoney()).thenReturn("Insufficient funds: {0}");
        when(fixture.economy.fractionalDigits()).thenReturn(0);
        when(fixture.economy.has(eq(fixture.player), anyDouble())).thenReturn(false);

        command(fixture);

        verify(fixture.economy).has(fixture.player, 103.0);
        verify(fixture.player).sendMessage(MessageFormat.format("Insufficient funds: {0}", 103.0));
        assertEquals(103.0, fixture.record().getValue());
        assertEquals(MarketTransactionStatus.ABORTED, fixture.record().getStatus());
        verify(fixture.economy, never()).withdrawPlayer(any(OfflinePlayer.class), anyDouble());
    }

    private FinancialCommandFixture fixture(boolean create, String name, double value) {
        FinancialCommandFixture fixture = new FinancialCommandFixture(temp.resolve(name), create);
        when(fixture.plugin.getMessages().getInsufficientMoney()).thenReturn("Insufficient funds: {0}");
        if (create) {
            when(fixture.companies.getCompanyCreationTax(13, 7.25, 1)).thenReturn(value);
            when(fixture.companies.removeSharesFromCompany(1, 13)).thenReturn(true);
            when(fixture.players.addSharesToPlayer(fixture.playerId, fixture.company, 13)).thenReturn(true);
        } else {
            when(fixture.company.getTotalShares()).thenReturn(20);
            when(fixture.company.getCurrentSharePrice()).thenReturn(value / 20);
            when(fixture.players.hasSharesInCompany(fixture.playerId, 1, 20)).thenReturn(true);
            when(fixture.players.removeSharesFromPlayer(fixture.playerId, 1, 20)).thenReturn(true);
        }
        return fixture;
    }

    private boolean command(FinancialCommandFixture fixture) {
        return fixture.create
                ? new CreateCommand(fixture.plugin).onCommand(fixture.player, new String[]{"Acme", "1", "13", "7.25"})
                : new DeleteCommand(fixture.plugin).onCommand(fixture.player, new String[]{"1"});
    }

    private EconomyResponse success(double value) {
        return new EconomyResponse(value, 1000, EconomyResponse.ResponseType.SUCCESS, null);
    }
}

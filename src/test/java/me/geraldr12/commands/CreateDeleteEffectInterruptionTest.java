package me.geraldr12.commands;

import me.geraldr12.persistence.*;
import me.geraldr12.utils.MarketTransactionLocks;
import net.milkbowl.vault.economy.EconomyResponse;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CreateDeleteEffectInterruptionTest {
    @TempDir Path temp;

    private List<String> types(boolean create) {
        return create ? List.of("ECONOMY_DEBIT", "COMPANY_RECORD_CREATE", "COMPANY_SHARE_REMOVAL", "PLAYER_SHARE_ADD")
                : List.of("ECONOMY_CREDIT", "PLAYER_SHARE_REMOVAL", "COMPANY_RECORD_DELETE");
    }

    @TestFactory
    Stream<DynamicTest> interruptions() {
        return Stream.of(true, false).flatMap(create -> Stream.concat(
                Stream.of("success", "rejected", "vaultThrows", "vaultThrowsBefore", "rejectedAfterChange",
                        "mismatch", "nanAmount", "nullResponse", "complete"),
                IntStream.range(0, types(create).size()).boxed().flatMap(i -> i == 0
                        ? Stream.of("request-" + i, "outcome-" + i)
                        : Stream.of("request-" + i, "outcome-" + i, "persistBefore-" + i, "persistAfter-" + i,
                                "persistThrows-" + i)))
                .map(boundary -> DynamicTest.dynamicTest((create ? "CREATE/" : "DELETE/") + boundary,
                        () -> check(create, boundary))));
    }

    private void check(boolean create, String boundary) {
        FinancialCommandFixture f = new FinancialCommandFixture(temp.resolve(create + boundary), create);
        double[] balance = {100.0};
        boolean[] companyExists = {!create};
        int[] companyShares = {create ? 10 : 0};
        int[] playerShares = {create ? 0 : 10};
        int[] writes = {0};
        int index = boundary.contains("-") ? Integer.parseInt(boundary.substring(boundary.indexOf('-') + 1)) : -1;
        List<String> types = types(create);
        doAnswer(invocation -> {
            f.prepared = invocation.getArgument(0);
            return invocation.callRealMethod();
        }).when(f.journal).prepareCurrentSchema(any());
        when(f.economy.getBalance(f.player)).thenAnswer(invocation -> balance[0]);
        org.mockito.stubbing.Answer<EconomyResponse> vault = invocation -> {
            if (boundary.equals("rejected")) return new EconomyResponse(0, balance[0], EconomyResponse.ResponseType.FAILURE, "rejected");
            if (boundary.equals("vaultThrowsBefore")) throw new IllegalStateException("before mutation");
            balance[0] += create ? -f.value : f.value;
            if (boundary.equals("rejectedAfterChange")) return new EconomyResponse(0, balance[0], EconomyResponse.ResponseType.FAILURE, "changed");
            if (boundary.equals("vaultThrows")) throw new IllegalStateException("after mutation");
            if (boundary.equals("nullResponse")) return null;
            return new EconomyResponse(boundary.equals("nanAmount") ? Double.NaN : boundary.equals("mismatch") ? f.value - 1 : f.value,
                    balance[0], EconomyResponse.ResponseType.SUCCESS, null);
        };
        if (create) when(f.economy.withdrawPlayer(f.player, f.value)).thenAnswer(vault);
        else when(f.economy.depositPlayer(f.player, f.value)).thenAnswer(vault);
        if (create) {
            when(f.companies.saveReservedCompany(f.company)).thenAnswer(invocation -> mutate(boundary, index, 1, writes,
                    () -> companyExists[0] = true));
            when(f.companies.removeSharesFromCompany(1, 10)).thenAnswer(invocation -> mutate(boundary, index, 2, writes,
                    () -> companyShares[0] = 0));
            when(f.players.addSharesToPlayer(f.playerId, f.company, 10)).thenAnswer(invocation -> mutate(boundary, index, 3, writes,
                    () -> playerShares[0] = 10));
        } else {
            when(f.players.removeSharesFromPlayer(f.playerId, 1, 10)).thenAnswer(invocation -> mutate(boundary, index, 1, writes,
                    () -> playerShares[0] = 0));
            when(f.companies.deleteCompany(1)).thenAnswer(invocation -> mutate(boundary, index, 2, writes,
                    () -> companyExists[0] = false));
        }
        if (boundary.startsWith("request-")) {
            doReturn(false).when(f.journal).requestEffect(any(), eq(MarketTransactionEffectType.valueOf(types.get(index))),
                    anyInt(), anyDouble(), anyInt());
        }
        if (boundary.startsWith("outcome-")) {
            doAnswer(invocation -> {
                MarketTransactionRecord record = invocation.getArgument(0);
                String id = invocation.getArgument(1);
                boolean fail = record.getEffects().stream().anyMatch(effect -> id.equals(effect.getEffectId())
                        && effect.getType().name().equals(types.get(index)));
                return fail ? false : invocation.callRealMethod();
            }).when(f.journal).recordEffectOutcome(any(), anyString(), any(), any());
        }
        if (boundary.equals("complete")) doReturn(false).when(f.journal).complete(any());
        try {
            f.command();
            boolean beforeMoney = boundary.equals("request-0");
            boolean rejected = boundary.equals("rejected");
            int confirmed = boundary.equals("success") || boundary.equals("complete") ? types.size()
                    : index >= 0 ? index : 0;
            boolean requested = index >= 0 && !boundary.startsWith("request-");
            int attempted = confirmed + (requested ? 1 : 0);
            if (index < 0 && !boundary.equals("success") && !boundary.equals("complete")) attempted = 1;
            boolean mutatedLast = boundary.startsWith("outcome-") || boundary.startsWith("persistAfter-")
                    || boundary.startsWith("persistThrows-");
            int mutated = confirmed + (mutatedLast ? 1 : 0);
            if (index < 0 && !boundary.equals("success") && !boundary.equals("complete")) mutated = rejected ? 0 : 1;
            assertEquals(beforeMoney || rejected || boundary.equals("vaultThrowsBefore")
                    ? 100.0 : 100.0 + (create ? -f.value : f.value), balance[0]);
            assertEquals(create ? mutated >= 2 : mutated < 3, companyExists[0]);
            assertEquals(create && mutated < 3 ? 10 : 0, companyShares[0]);
            assertEquals(create ? (mutated >= 4 ? 10 : 0) : (mutated >= 2 ? 0 : 10), playerShares[0]);
            f.verifyVault(beforeMoney ? 0 : 1);
            MarketTransactionRecord record = f.record();
            assertTrue(record.isSchema3());
            assertFalse(record.isLegacyMigrated());
            assertEquals(f.value, record.getQuoteTotalValue());
            assertEquals(2.0, record.getQuoteUnitSharePrice());
            assertEquals(10, record.getQuoteQuantity());
            MarketTransactionStatus status = boundary.equals("success") ? MarketTransactionStatus.COMPLETED
                    : beforeMoney || rejected ? MarketTransactionStatus.ABORTED : MarketTransactionStatus.RECONCILIATION_REQUIRED;
            assertEquals(status, record.getStatus());
            assertEquals(attempted, record.getEffects().size());
            for (int i = 0; i < attempted; i++) {
                MarketTransactionEffect effect = record.getEffects().get(i);
                assertEquals(types.get(i), effect.getType().name());
                assertEquals((i + 1) * 10, effect.getSequence());
                MarketTransactionEffectState state = i < confirmed ? MarketTransactionEffectState.CONFIRMED
                        : rejected ? MarketTransactionEffectState.DEFINITE_REJECTED
                        : boundary.startsWith("outcome-") ? MarketTransactionEffectState.REQUESTED
                        : MarketTransactionEffectState.AMBIGUOUS;
                assertEquals(state, effect.getState());
            }
            verify(f.journal, never()).markEconomyApplied(any());
            verify(f.journal, never()).markStateCommitted(any());
            verify(f.journal, never()).markMarketImpactStarted(any());
            verify(f.journal, never()).markMarketImpactApplied(any());
            boolean blocked = status == MarketTransactionStatus.RECONCILIATION_REQUIRED;
            assertEquals(blocked, MarketTransactionLocks.requiresPlayerReconciliation(f.playerId));
            assertEquals(blocked, MarketTransactionLocks.requiresReconciliation(UUID.randomUUID(), 1));
            if (blocked) {
                int previousWrites = writes[0];
                f.command();
                assertEquals(previousWrites, writes[0]);
                f.verifyVault(1);
                org.bukkit.entity.Player admin = mock(org.bukkit.entity.Player.class);
                when(admin.hasPermission(anyString())).thenReturn(true);
                when(admin.getName()).thenReturn("operator");
                ReconcileCommand reconcile = new ReconcileCommand(f.plugin);
                reconcile.execute(admin, new String[]{"list"});
                verify(admin).sendMessage(record.getTransactionId() + " " + record.getOperation() + " " + status);
                reconcile.execute(admin, new String[]{"show", record.getTransactionId()});
                reconcile.execute(admin, new String[]{"investigate", "inspect", record.getTransactionId()});
                for (MarketTransactionEffect effect : record.getEffects()) {
                    if (effect.getState() != MarketTransactionEffectState.CONFIRMED) {
                        verify(admin).sendMessage("effect=" + effect.getEffectId() + " type=" + effect.getType()
                                + " state=" + effect.getState());
                    }
                }
                reconcile.execute(admin, new String[]{"resolve", record.getTransactionId(), "acknowledge:inspected"});
                MarketTransactionRecord after = f.record();
                assertEquals(record.getEffects(), after.getEffects());
                assertEquals(status, after.getStatus());
            }
            System.out.printf("OBSERVED %s/%s status=%s effects=%s balance=%.2f company=%s companyShares=%d wallet=%d writes=%d%n",
                    create ? "CREATE" : "DELETE", boundary, status,
                    record.getEffects().stream().map(e -> e.getType() + ":" + e.getState()).toList(),
                    balance[0], companyExists[0], companyShares[0], playerShares[0], writes[0]);
        } finally {
            assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
            MarketTransactionLocks.revoke();
        }
    }

    @TestFactory
    Stream<DynamicTest> migratedRecordsRemainInspectableAndBlocked() {
        return Stream.of(true, false).map(create -> DynamicTest.dynamicTest("migrated/" + create, () -> {
            FinancialCommandFixture f = new FinancialCommandFixture(temp.resolve("migrated" + create), create);
            try {
                MarketTransactionRecord record = new MarketTransactionRecord();
                record.setTransactionId(UUID.randomUUID().toString());
                record.setOperation(create ? "CREATE" : "DELETE");
                record.setPlayerUuid(f.playerId.toString());
                record.setCompanyId(1L);
                record.setQuantity(10);
                record.setValue(f.value);
                record.setTimestamp(System.currentTimeMillis());
                record.setStatus(MarketTransactionStatus.ECONOMY_APPLIED);
                assertNotNull(f.journal.migrateLegacyRecord(record));
                assertTrue(new me.geraldr12.data.sources.yml.YmlDataSourceImpl<MarketTransactionRecord>(f.root.toFile())
                        .save("transactions/" + record.getTransactionId() + ".yml", record));
                PlayerInspector inspector = new PlayerInspector(f);
                inspector.command.execute(inspector.admin, new String[]{"list"});
                verify(inspector.admin).sendMessage(record.getTransactionId() + " " + record.getOperation()
                        + " RECONCILIATION_REQUIRED");
                inspector.command.execute(inspector.admin, new String[]{"investigate", "inspect", record.getTransactionId()});
                for (MarketTransactionEffect effect : record.getEffects().subList(1, record.getEffects().size())) {
                    verify(inspector.admin).sendMessage("effect=" + effect.getEffectId() + " type=" + effect.getType() + " state=UNKNOWN");
                }
                inspector.command.execute(inspector.admin, new String[]{"resolve", record.getTransactionId(), "acknowledge:inspected"});
                MarketTransactionRecord after = f.journal.getRecord(record.getTransactionId()).orElseThrow();
                assertEquals(record.getEffects(), after.getEffects());
                assertEquals(MarketTransactionStatus.RECONCILIATION_REQUIRED, after.getStatus());
                f.command();
                f.verifyVault(0);
                assertTrue(MarketTransactionLocks.requiresPlayerReconciliation(f.playerId));
                assertTrue(MarketTransactionLocks.requiresReconciliation(UUID.randomUUID(), 1));
            } finally {
                MarketTransactionLocks.revoke();
            }
        }));
    }

    private static class PlayerInspector {
        final org.bukkit.entity.Player admin = mock(org.bukkit.entity.Player.class);
        final ReconcileCommand command;

        PlayerInspector(FinancialCommandFixture fixture) {
            when(admin.hasPermission(anyString())).thenReturn(true);
            when(admin.getName()).thenReturn("operator");
            command = new ReconcileCommand(fixture.plugin);
        }
    }

    private boolean mutate(String boundary, int index, int step, int[] writes, Runnable mutation) {
        writes[0]++;
        if (index == step && boundary.startsWith("persistBefore-")) return false;
        mutation.run();
        if (index == step && boundary.startsWith("persistThrows-")) throw new IllegalStateException("after persistence");
        return !(index == step && boundary.startsWith("persistAfter-"));
    }
}

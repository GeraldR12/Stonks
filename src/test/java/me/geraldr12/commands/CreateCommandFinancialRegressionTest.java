package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.data.services.CompaniesService;
import me.geraldr12.data.services.PlayersService;
import me.geraldr12.data.sources.yml.YmlDataSourceImpl;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.persistence.MarketTransactionRecord;
import me.geraldr12.persistence.MarketTransactionStatus;
import me.geraldr12.utils.MarketTransactionLocks;
import me.geraldr12.utils.Messages;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CreateCommandFinancialRegressionTest {
    @TempDir Path temp;

    @TestFactory
    Stream<DynamicTest> boundaries() {
        return FinancialCommandFixture.boundaries(true).map(boundary -> DynamicTest.dynamicTest(boundary,
                () -> new FinancialCommandFixture(temp.resolve(boundary), true).checkBoundary(boundary)));
    }

    @TestFactory
    Stream<DynamicTest> restart() {
        return FinancialCommandFixture.restarts(temp, true);
    }

    @Test
    void prepareFailureShouldNotifyPlayer() {
        FinancialCommandFixture fixture = new FinancialCommandFixture(temp, true);
        fixture.inject("prepare");
        fixture.command();
        verify(fixture.player).sendMessage("Unable to prepare the transaction; try again later.");
    }

    @Test
    void economyMarkerFailureShouldNotifyPlayer() {
        FinancialCommandFixture fixture = new FinancialCommandFixture(temp, true);
        fixture.inject("economy");
        fixture.command();
        verify(fixture.player).sendMessage("CRITICAL: company creation requires administrator reconciliation.");
    }

    @TestFactory
    Stream<DynamicTest> persistenceReasonsShouldIdentifyStep() {
        return Stream.of("save", "companyShares", "playerShares").map(step -> DynamicTest.dynamicTest(step, () -> {
            FinancialCommandFixture fixture = new FinancialCommandFixture(temp.resolve(step), true);
            fixture.inject(step);
            fixture.command();
            assertTrue(fixture.record().getFailureReason().contains(step));
        }));
    }
}

class FinancialCommandFixture {
    static final MarketTransactionStatus[] RESTART_STATUSES = {
            MarketTransactionStatus.PREPARED, MarketTransactionStatus.IN_PROGRESS,
            MarketTransactionStatus.ECONOMY_APPLIED, MarketTransactionStatus.STATE_COMMITTED,
            MarketTransactionStatus.MARKET_IMPACT_STARTED, MarketTransactionStatus.MARKET_IMPACT_APPLIED,
            MarketTransactionStatus.RECONCILIATION_REQUIRED, MarketTransactionStatus.COMPENSATING
    };

    final Stonks plugin = mock(Stonks.class);
    final Player player = mock(Player.class);
    final Economy economy = mock(Economy.class);
    final CompaniesService companies = mock(CompaniesService.class);
    final PlayersService players = mock(PlayersService.class);
    final CompanyDao company = mock(CompanyDao.class);
    final UUID playerId = UUID.randomUUID();
    final MarketTransactionJournal journal;
    final Path root;
    final boolean create;
    final double value;
    MarketTransactionRecord prepared;

    FinancialCommandFixture(Path root, boolean create) {
        this.root = root;
        this.create = create;
        value = create ? 22.0 : 20.0;
        journal = spy(new MarketTransactionJournal(root.toFile()));
        Messages messages = mock(Messages.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("FinancialCommandRegression"));
        when(plugin.getMessages()).thenReturn(messages);
        when(messages.getPluginPrefix()).thenReturn("");
        when(messages.getCreatedCompany()).thenReturn("Created {0}");
        when(messages.getSoldActions()).thenReturn("Sold {0} for {1}");
        when(messages.getDeletedCompany()).thenReturn("Deleted {0}");
        when(plugin.getEconomy()).thenReturn(economy);
        when(plugin.getCompaniesService()).thenReturn(companies);
        when(plugin.getPlayersService()).thenReturn(players);
        when(plugin.getTransactionJournal()).thenReturn(journal);
        when(plugin.isJournalReady()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(playerId);
        when(company.getId()).thenReturn(1);
        when(company.getTotalShares()).thenReturn(10);
        when(company.getCurrentSharePrice()).thenReturn(2.0);
        when(company.getName()).thenReturn("Acme");
        when(companies.reserveCompanyId(any())).thenReturn(company);
        when(companies.getCompanyCreationTax(10, 2.0, 1)).thenReturn(22.0);
        when(companies.getCompanyById(1)).thenReturn(company);
        when(players.hasSharesInCompany(playerId, 1, 10)).thenReturn(true);
        when(companies.saveReservedCompany(company)).thenReturn(true);
        when(companies.removeSharesFromCompany(1, 10)).thenReturn(true);
        when(players.addSharesToPlayer(playerId, company, 10)).thenReturn(true);
        when(players.removeSharesFromPlayer(playerId, 1, 10)).thenReturn(true);
        when(companies.deleteCompany(1)).thenReturn(true);
        when(economy.has(player, value)).thenReturn(true);
        vaultResponse(new EconomyResponse(value, 100, EconomyResponse.ResponseType.SUCCESS, null));
        doAnswer(invocation -> {
            prepared = invocation.getArgument(0);
            return invocation.callRealMethod();
        }).when(journal).prepare(any());
        MarketTransactionLocks.initialize(journal);
        assertTrue(MarketTransactionLocks.activate(plugin, journal));
    }

    static Stream<String> boundaries(boolean create) {
        return Stream.concat(Stream.of("prepare", "vaultException", "rejected", "nullResponse", "mismatch",
                        "nanAmount", "economy", "state", "impactStarted", "impactApplied", "complete",
                        "reconciliationFalse", "economyAndReconciliationFalse", "stateReconciliationFalse",
                        "impactStartedReconciliationFalse", "impactAppliedReconciliationFalse",
                        "completeReconciliationFalse", "success"),
                create ? Stream.of("balanceException", "save", "companyShares", "playerShares")
                        : Stream.of("playerShares", "delete"));
    }

    void command() {
        if (create) new CreateCommand(plugin).onCommand(player, new String[]{"Acme", "1", "10", "2.0"});
        else new DeleteCommand(plugin).onCommand(player, new String[]{"1"});
    }

    void vaultResponse(EconomyResponse response) {
        if (create) when(economy.withdrawPlayer(player, value)).thenReturn(response);
        else when(economy.depositPlayer(player, value)).thenReturn(response);
    }

    void inject(String boundary) {
        if (boundary.endsWith("ReconciliationFalse") && !boundary.equals("economyAndReconciliationFalse")) {
            inject(boundary.substring(0, boundary.indexOf("ReconciliationFalse")));
            doReturn(false).when(journal).markReconciliationRequired(any(), anyString());
            return;
        }
        switch (boundary) {
            case "prepare" -> doReturn(null).when(journal).prepare(any());
            case "balanceException" -> when(economy.has(player, value)).thenThrow(new IllegalStateException("balance"));
            case "vaultException" -> {
                if (create) when(economy.withdrawPlayer(player, value)).thenThrow(new IllegalStateException("withdraw"));
                else when(economy.depositPlayer(player, value)).thenThrow(new IllegalStateException("deposit"));
            }
            case "rejected" -> vaultResponse(new EconomyResponse(0, 100, EconomyResponse.ResponseType.FAILURE, "rejected"));
            case "nullResponse" -> vaultResponse(null);
            case "mismatch" -> vaultResponse(new EconomyResponse(value - 1, 100, EconomyResponse.ResponseType.SUCCESS, null));
            case "nanAmount" -> vaultResponse(new EconomyResponse(Double.NaN, 100, EconomyResponse.ResponseType.SUCCESS, null));
            case "economy", "economyAndReconciliationFalse" -> doReturn(false).when(journal).markEconomyApplied(any());
            case "save" -> when(companies.saveReservedCompany(company)).thenReturn(false);
            case "companyShares" -> when(companies.removeSharesFromCompany(1, 10)).thenReturn(false);
            case "playerShares" -> {
                if (create) when(players.addSharesToPlayer(playerId, company, 10)).thenReturn(false);
                else when(players.removeSharesFromPlayer(playerId, 1, 10)).thenReturn(false);
            }
            case "delete" -> when(companies.deleteCompany(1)).thenReturn(false);
            case "state" -> doReturn(false).when(journal).markStateCommitted(any());
            case "impactStarted" -> doReturn(false).when(journal).markMarketImpactStarted(any());
            case "impactApplied" -> doReturn(false).when(journal).markMarketImpactApplied(any());
            case "complete" -> doReturn(false).when(journal).complete(any());
            case "reconciliationFalse" -> inject(create ? "save" : "playerShares");
            case "success" -> { }
            default -> throw new IllegalArgumentException(boundary);
        }
        if (boundary.equals("reconciliationFalse") || boundary.equals("economyAndReconciliationFalse")) {
            doReturn(false).when(journal).markReconciliationRequired(any(), anyString());
        }
    }

    MarketTransactionRecord record() {
        assertNotNull(prepared);
        return journal.getRecord(prepared.getTransactionId()).orElseGet(() -> {
            MarketTransactionRecord archived = new YmlDataSourceImpl<MarketTransactionRecord>(root.toFile())
                    .load("transactions/archive/" + prepared.getTransactionId() + ".yml", MarketTransactionRecord.class);
            assertNotNull(archived);
            return archived;
        });
    }

    void verifyVault(int count) {
        if (create) {
            verify(economy, times(count)).withdrawPlayer(player, value);
            verify(economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
        } else {
            verify(economy, times(count)).depositPlayer(player, value);
            verify(economy, never()).withdrawPlayer(any(OfflinePlayer.class), anyDouble());
        }
        long mutations = mockingDetails(economy).getInvocations().stream().filter(invocation -> {
            String name = invocation.getMethod().getName();
            return name.startsWith("withdraw") || name.startsWith("deposit");
        }).count();
        assertEquals(count, mutations);
    }

    void checkBoundary(String boundary) {
        try {
            inject(boundary);
            command();
            int count = boundary.equals("prepare") || boundary.equals("balanceException") ? 0 : 1;
            verifyVault(count);
            int stage = switch (boundary) {
                case "prepare", "balanceException", "vaultException", "rejected", "nullResponse", "mismatch",
                        "nanAmount", "economy", "economyAndReconciliationFalse" -> 0;
                case "save", "reconciliationFalse" -> 1;
                case "companyShares", "delete" -> 2;
                case "playerShares" -> create ? 3 : 1;
                default -> 3;
            };
            if (create) {
                verify(companies, times(stage >= 1 ? 1 : 0)).saveReservedCompany(company);
                verify(companies, times(stage >= 2 ? 1 : 0)).removeSharesFromCompany(1, 10);
                verify(players, times(stage >= 3 ? 1 : 0)).addSharesToPlayer(playerId, company, 10);
            } else {
                verify(players, times(stage >= 1 ? 1 : 0)).removeSharesFromPlayer(playerId, 1, 10);
                verify(companies, times(stage >= 2 ? 1 : 0)).deleteCompany(1);
            }
            if (boundary.equals("prepare")) {
                assertTrue(journal.getAllPendingOrReconciliationRecords().isEmpty());
                if (create) verify(player).sendMessage("Unable to prepare the transaction; try again later.");
                else verify(player).sendMessage("Unable to prepare the transaction; try again later.");
                assertFalse(MarketTransactionLocks.requiresPlayerReconciliation(playerId));
                return;
            }
            MarketTransactionStatus status = switch (boundary) {
                case "balanceException", "rejected", "nullResponse" -> MarketTransactionStatus.ABORTED;
                case "reconciliationFalse" -> MarketTransactionStatus.ECONOMY_APPLIED;
                case "economyAndReconciliationFalse" -> MarketTransactionStatus.PREPARED;
                case "stateReconciliationFalse" -> MarketTransactionStatus.ECONOMY_APPLIED;
                case "impactStartedReconciliationFalse" -> MarketTransactionStatus.STATE_COMMITTED;
                case "impactAppliedReconciliationFalse" -> MarketTransactionStatus.MARKET_IMPACT_STARTED;
                case "completeReconciliationFalse" -> MarketTransactionStatus.MARKET_IMPACT_APPLIED;
                case "success" -> MarketTransactionStatus.COMPLETED;
                default -> MarketTransactionStatus.RECONCILIATION_REQUIRED;
            };
            assertEquals(status, record().getStatus());
            boolean blocked = status != MarketTransactionStatus.ABORTED && status != MarketTransactionStatus.COMPLETED;
            assertEquals(blocked, MarketTransactionLocks.requiresPlayerReconciliation(playerId));
            assertEquals(blocked, MarketTransactionLocks.requiresReconciliation(playerId, 2));
            assertEquals(blocked, MarketTransactionLocks.requiresReconciliation(UUID.randomUUID(), 1));
            assertFalse(MarketTransactionLocks.requiresReconciliation(UUID.randomUUID(), 2));
            if (create && boundary.equals("economy")) verify(player).sendMessage("CRITICAL: company creation requires administrator reconciliation.");
            if (create && Arrays.asList("save", "companyShares", "playerShares").contains(boundary)) {
                assertEquals("Creation persistence failed after economy mutation: " + boundary, record().getFailureReason());
            }
            if (blocked) {
                command();
                verifyVault(count);
                verify(player).sendMessage("Transactions are temporarily unavailable or require reconciliation; try again later.");
            }
        } finally {
            assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
            MarketTransactionLocks.revoke();
        }
    }

    static Stream<DynamicTest> restarts(Path temp, boolean create) {
        return Arrays.stream(RESTART_STATUSES).flatMap(status -> Stream.of("samePlayer", "sameCompany", "both")
                .map(identity -> DynamicTest.dynamicTest(status + "/" + identity, () -> {
                    FinancialCommandFixture fixture = new FinancialCommandFixture(temp.resolve(status + "-" + identity), create);
                    try {
                        MarketTransactionRecord seed = new MarketTransactionRecord();
                        seed.setTransactionId(UUID.randomUUID().toString());
                        seed.setOperation(create ? "CREATE" : "DELETE");
                        seed.setPlayerUuid((identity.equals("sameCompany") ? UUID.randomUUID() : fixture.playerId).toString());
                        seed.setCompanyId(identity.equals("samePlayer") ? 2L : 1L);
                        seed.setQuantity(10);
                        seed.setValue(fixture.value);
                        seed.setTimestamp(System.currentTimeMillis());
                        assertNotNull(fixture.journal.prepare(seed));
                        seed.setStatus(status);
                        assertTrue(new YmlDataSourceImpl<MarketTransactionRecord>(fixture.root.toFile())
                                .save("transactions/" + seed.getTransactionId() + ".yml", seed));
                        MarketTransactionJournal reloaded = new MarketTransactionJournal(fixture.root.toFile());
                        assertTrue(reloaded.recoverTerminalPendingRecords());
                        MarketTransactionLocks.revoke();
                        when(fixture.plugin.getTransactionJournal()).thenReturn(reloaded);
                        MarketTransactionLocks.initialize(reloaded);
                        assertTrue(MarketTransactionLocks.activate(fixture.plugin, reloaded));
                        // Na inicializacao todo registro pendente nao terminal foi interrompido: escala para
                        // RECONCILIATION_REQUIRED (continua bloqueando; o admin consegue registrar o reconhecimento).
                        assertEquals(MarketTransactionStatus.RECONCILIATION_REQUIRED,
                                reloaded.getRecord(seed.getTransactionId()).orElseThrow().getStatus());
                        fixture.command();
                        fixture.verifyVault(0);
                        verify(fixture.player).sendMessage("Transactions are temporarily unavailable or require reconciliation; try again later.");
                        verify(fixture.companies, never()).saveReservedCompany(any());
                        verify(fixture.companies, never()).deleteCompany(anyLong());
                        assertEquals(MarketTransactionStatus.RECONCILIATION_REQUIRED,
                                reloaded.getRecord(seed.getTransactionId()).orElseThrow().getStatus());
                    } finally {
                        assertEquals(0, MarketTransactionLocks.activeAdmissionCount());
                        MarketTransactionLocks.revoke();
                    }
                })));
    }
}

package me.geraldr12.data.services;

import me.geraldr12.Stonks;
import me.geraldr12.data.dao.CompanyDao;
import me.geraldr12.economy.EconomyMutationOutcome;
import me.geraldr12.economy.EconomyMutationResult;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Every funds action moves gold between real balances; the sum of all balances never grows. */
class CompanyFundsActionsTest {
    @TempDir Path temp;

    private Stonks plugin;
    private CompanyFundsService funds;
    private FakeWallet wallet;
    private TreasuryServiceTest.FakeGateway treasuryGateway;
    private TreasuryService treasury;
    private FileConfiguration config;
    private PlayersService players;
    private CompanyDao company;
    private final UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID investor = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final long companyId = 5;

    /** Player balances in memory; OfflinePlayer mocks are keyed by UUID. */
    static final class FakeWallet implements CompanyFundsActions.Wallet {
        final Map<UUID, Double> balances = new HashMap<>();
        EconomyMutationOutcome next = null;
        final List<String> calls = new ArrayList<>();

        @Override public int fractionalDigits() { return 0; }

        @Override public EconomyMutationResult withdraw(OfflinePlayer player, double amount) {
            calls.add("withdraw " + amount);
            if (next != null) return result(next, amount);
            double balance = balances.getOrDefault(player.getUniqueId(), 0.0);
            if (balance < amount) return result(EconomyMutationOutcome.DEFINITE_REJECTED, amount);
            balances.put(player.getUniqueId(), balance - amount);
            return result(EconomyMutationOutcome.CONFIRMED, amount);
        }

        @Override public EconomyMutationResult deposit(OfflinePlayer player, double amount) {
            calls.add("deposit " + amount);
            if (next != null) return result(next, amount);
            balances.merge(player.getUniqueId(), amount, Double::sum);
            return result(EconomyMutationOutcome.CONFIRMED, amount);
        }

        static EconomyMutationResult result(EconomyMutationOutcome outcome, double amount) {
            return new EconomyMutationResult(outcome, amount, amount, "test", null, null);
        }
    }

    @BeforeEach
    void setUp() {
        plugin = mock(Stonks.class);
        funds = new CompanyFundsService(() -> true, temp.resolve("funds/ledger.yml"));
        wallet = new FakeWallet();
        treasuryGateway = new TreasuryServiceTest.FakeGateway();
        treasury = new TreasuryService(treasuryGateway, temp.resolve("treasury/ledger.yml"));
        config = mock(FileConfiguration.class);
        when(config.getDouble(eq("Stonks.Taxes.Dividend"), anyDouble())).thenReturn(0.0);
        when(config.getDouble(eq("Stonks.CompanyFunds.ShopRevenueShare"), anyDouble())).thenReturn(0.05);
        players = mock(PlayersService.class);
        CompaniesService companies = mock(CompaniesService.class);
        company = mock(CompanyDao.class);
        when(company.getOwnerUuid()).thenReturn(owner.toString());
        when(company.isBankrupt()).thenReturn(false);
        when(companies.getCompanyById(companyId)).thenReturn(company);
        when(plugin.getCompanyFundsService()).thenReturn(funds);
        when(plugin.getTreasuryService()).thenReturn(treasury);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getPlayersService()).thenReturn(players);
        when(plugin.getCompaniesService()).thenReturn(companies);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
    }

    private CompanyFundsActions actions() {
        return new CompanyFundsActions(plugin, funds, wallet, id -> {
            OfflinePlayer player = mock(OfflinePlayer.class);
            when(player.getUniqueId()).thenReturn(id);
            return player;
        });
    }

    private void shareholders(Map<UUID, Integer> holders) {
        when(players.getShareholdersForCompany(companyId)).thenReturn(holders);
    }

    @Test
    void ownerDepositMovesGoldFromTheOwnerIntoTheCompany() {
        wallet.balances.put(owner, 100.0);
        CompanyFundsActions.Result result = actions().deposit(owner, companyId, 40.9);
        assertEquals(CompanyFundsActions.Status.SUCCESS, result.status());
        assertEquals(60.0, wallet.balances.get(owner));
        assertEquals(40.0, funds.balance(companyId));
    }

    @Test
    void onlyTheOwnerCanDeposit() {
        wallet.balances.put(investor, 100.0);
        assertEquals(CompanyFundsActions.Status.NOT_OWNER, actions().deposit(investor, companyId, 10).status());
        assertEquals(100.0, wallet.balances.get(investor));
        assertEquals(0.0, funds.balance(companyId));
    }

    @Test
    void depositWithoutEnoughGoldChangesNothing() {
        wallet.balances.put(owner, 5.0);
        assertEquals(CompanyFundsActions.Status.INSUFFICIENT_PLAYER_BALANCE,
                actions().deposit(owner, companyId, 10).status());
        assertEquals(0.0, funds.balance(companyId));
    }

    @Test
    void ambiguousDepositCreditsNothing() {
        wallet.next = EconomyMutationOutcome.AMBIGUOUS;
        assertEquals(CompanyFundsActions.Status.NEEDS_REVIEW, actions().deposit(owner, companyId, 10).status());
        assertEquals(0.0, funds.balance(companyId));
    }

    @Test
    void dividendIsSplitPerShareAndTheRemainderStays() {
        funds.credit(companyId, 100.0, "seed");
        Map<UUID, Integer> holders = new LinkedHashMap<>();
        holders.put(owner, 70);
        holders.put(investor, 30);
        shareholders(holders);
        CompanyFundsActions.Result result = actions().dividend(owner, companyId, 10);
        assertEquals(CompanyFundsActions.Status.SUCCESS, result.status());
        assertEquals(7.0, wallet.balances.get(owner));
        assertEquals(3.0, wallet.balances.get(investor));
        assertEquals(90.0, funds.balance(companyId));
        assertEquals(2, result.paidHolders());
        assertFalse(funds.hasPending(companyId));
    }

    @Test
    void dividendRoundsDownEachShareAndKeepsWhatCannotBePaid() {
        funds.credit(companyId, 100.0, "seed");
        Map<UUID, Integer> holders = new LinkedHashMap<>();
        holders.put(owner, 2);
        holders.put(investor, 1);
        shareholders(holders);
        actions().dividend(owner, companyId, 10);
        assertEquals(6.0, wallet.balances.get(owner));
        assertEquals(3.0, wallet.balances.get(investor));
        assertEquals(91.0, funds.balance(companyId));
    }

    @Test
    void dividendTaxGoesToTheTreasury() {
        when(config.getDouble(eq("Stonks.Taxes.Dividend"), anyDouble())).thenReturn(0.1);
        funds.credit(companyId, 100.0, "seed");
        shareholders(Map.of(investor, 10));
        actions().dividend(owner, companyId, 50);
        assertEquals(List.of(5.0), treasuryGateway.deposits);
        assertEquals(45.0, wallet.balances.get(investor));
        assertEquals(50.0, funds.balance(companyId));
    }

    @Test
    void dividendCannotExceedTheFunds() {
        funds.credit(companyId, 20.0, "seed");
        shareholders(Map.of(investor, 10));
        assertEquals(CompanyFundsActions.Status.INSUFFICIENT_COMPANY_FUNDS,
                actions().dividend(owner, companyId, 21).status());
        assertTrue(wallet.calls.isEmpty());
    }

    @Test
    void onlyTheOwnerDeclaresDividends() {
        funds.credit(companyId, 20.0, "seed");
        shareholders(Map.of(investor, 10));
        assertEquals(CompanyFundsActions.Status.NOT_OWNER, actions().dividend(investor, companyId, 10).status());
        assertEquals(20.0, funds.balance(companyId));
    }

    @Test
    void ambiguousDividendPayoutStopsAndStaysPending() {
        funds.credit(companyId, 100.0, "seed");
        Map<UUID, Integer> holders = new LinkedHashMap<>();
        holders.put(owner, 1);
        holders.put(investor, 1);
        shareholders(holders);
        wallet.next = EconomyMutationOutcome.AMBIGUOUS;
        CompanyFundsActions.Result result = actions().dividend(owner, companyId, 10);
        assertEquals(CompanyFundsActions.Status.NEEDS_REVIEW, result.status());
        assertEquals(1, wallet.calls.size());
        assertTrue(funds.hasPending(companyId));
        assertEquals(95.0, funds.balance(companyId));
        assertEquals(100.0, funds.totalHeld());
    }

    @Test
    void refusedDividendPayoutReturnsToTheCompany() {
        funds.credit(companyId, 100.0, "seed");
        shareholders(Map.of(investor, 1));
        wallet.next = EconomyMutationOutcome.DEFINITE_REJECTED;
        actions().dividend(owner, companyId, 10);
        assertEquals(100.0, funds.balance(companyId));
        assertFalse(funds.hasPending(companyId));
    }

    @Test
    void linkedShopSaleChargesTheShopOwnerIntoTheCompany() {
        wallet.balances.put(investor, 200.0);
        CompanyFundsActions.Result result = actions().shopShare(investor, companyId, 100.0, "SHOP:1");
        assertEquals(CompanyFundsActions.Status.SUCCESS, result.status());
        assertEquals(195.0, wallet.balances.get(investor));
        assertEquals(5.0, funds.balance(companyId));
        actions().shopShare(investor, companyId, 100.0, "SHOP:1");
        assertEquals(5.0, funds.balance(companyId), "the same sale is never counted twice");
        assertEquals(195.0, wallet.balances.get(investor), "and the shop owner is never charged twice");
    }

    @Test
    void tinyShopSaleIsIgnored() {
        wallet.balances.put(investor, 200.0);
        assertEquals(CompanyFundsActions.Status.TOO_SMALL,
                actions().shopShare(investor, companyId, 10.0, "SHOP:2").status());
        assertTrue(wallet.calls.isEmpty());
    }

    @Test
    void shopOwnerWithoutGoldPaysNothingAndNothingIsCredited() {
        wallet.balances.put(investor, 1.0);
        assertEquals(CompanyFundsActions.Status.REJECTED,
                actions().shopShare(investor, companyId, 100.0, "SHOP:3").status());
        assertEquals(0.0, funds.balance(companyId));
    }

    @Test
    void bankruptcySendsTheCashToTheTreasury() {
        funds.credit(companyId, 42.0, "seed");
        assertEquals(42.0, CompanyFundsActions.closeToTreasury(plugin, funds, companyId, "BANKRUPT"));
        assertEquals(List.of(42.0), treasuryGateway.deposits);
        assertEquals(0.0, funds.balance(companyId));
        assertFalse(funds.hasPending(companyId));
        assertEquals(0.0, funds.totalHeld());
    }

    @Test
    void closingAnEmptyCompanyMovesNothing() {
        assertEquals(0.0, CompanyFundsActions.closeToTreasury(plugin, funds, companyId, "BANKRUPT"));
        assertTrue(treasuryGateway.deposits.isEmpty());
    }

    @Test
    void grantMovesGoldFromTheTreasuryAccount() {
        TreasuryGrantGateway gateway = new TreasuryGrantGateway(500.0);
        TreasuryService backed = new TreasuryService(gateway, temp.resolve("t2/ledger.yml"));
        when(plugin.getTreasuryService()).thenReturn(backed);
        CompanyFundsActions.Result result = actions().grantFromTreasury(companyId, 120.0);
        assertEquals(CompanyFundsActions.Status.SUCCESS, result.status());
        assertEquals(380.0, gateway.balance);
        assertEquals(120.0, funds.balance(companyId));
        assertEquals(CompanyFundsActions.Status.INSUFFICIENT_PLAYER_BALANCE,
                actions().grantFromTreasury(companyId, 1000.0).status());
        assertEquals(380.0, gateway.balance);
    }

    @Test
    void pausedCompanyRefusesEveryAction() {
        funds.credit(companyId, 100.0, "seed");
        funds.reserve(companyId, 10.0, "stuck", "SELL", null);
        wallet.balances.put(owner, 100.0);
        shareholders(Map.of(owner, 1));
        assertEquals(CompanyFundsActions.Status.BLOCKED_PENDING_REVIEW, actions().deposit(owner, companyId, 5).status());
        assertEquals(CompanyFundsActions.Status.BLOCKED_PENDING_REVIEW, actions().dividend(owner, companyId, 5).status());
        assertEquals(CompanyFundsActions.Status.BLOCKED_PENDING_REVIEW,
                actions().shopShare(owner, companyId, 100, "SHOP:x").status());
        assertTrue(wallet.calls.isEmpty());
    }

    static final class TreasuryGrantGateway implements TreasuryService.Gateway {
        double balance;

        TreasuryGrantGateway(double balance) {
            this.balance = balance;
        }

        @Override public String account() { return "Server_Account"; }
        @Override public boolean deposit(String name, double amount) { balance += amount; return true; }
        @Override public int fractionalDigits() { return 0; }
        @Override public double balance(String name) { return balance; }
        @Override public boolean withdraw(String name, double amount) {
            if (balance < amount) return false;
            balance -= amount;
            return true;
        }
    }
}

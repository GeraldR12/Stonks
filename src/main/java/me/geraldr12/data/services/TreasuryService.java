package me.geraldr12.data.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends the fees Stonks charges (trading fee, company creation tax) to a server account
 * ({@code Stonks.Treasury.Account}). Players pay the same as before. With no account configured,
 * fees are removed from the economy.
 *
 * <p>A fee is credited only after its trade completed. A deposit that fails is kept as "owed" in
 * {@code treasury/ledger.yml} and retried on the next credit, so the account never gets more than
 * players paid and nothing is lost across restarts.</p>
 */
public class TreasuryService {

    public interface Gateway {
        /** The configured account name, or blank when the treasury is off. */
        String account();

        /** Deposits into the named account. Returns false when the economy refused or the account is missing. */
        boolean deposit(String account, double amount);

        /** Digits the currency keeps (-1 when unlimited). */
        int fractionalDigits();

        /** Current balance of the named account, or NaN when unknown. */
        default double balance(String account) {
            return Double.NaN;
        }

        /** Withdraws from the named account. Returns false when refused. */
        default boolean withdraw(String account, double amount) {
            return false;
        }
    }

    private static final Logger LOG = Logger.getLogger("Stonks");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Gateway gateway;
    private final Path ledger;
    private double credited;
    private double owed;
    private double paid;
    private double unbacked;
    private double pendingCredit;
    private double pendingPayout;
    private ShopSettlement pendingShop;
    private boolean storageUnavailable;

    public record ShopSettlement(String account, String player, String direction, double amount, double before) {
    }

    public TreasuryService(Gateway gateway, Path ledger) {
        this.gateway = gateway;
        this.ledger = ledger;
        load();
    }

    public synchronized boolean enabled() {
        String account = gateway.account();
        return account != null && !account.isBlank();
    }

    /**
     * Records a fee that players already paid and deposits it, together with anything still owed.
     * Never throws; a negative, zero or non-finite fee is ignored.
     */
    public synchronized void credit(double fee) {
        if (!enabled()) return;
        // Keep sub-unit remainders as owed; only whole currency units are deposited.
        if (!Double.isFinite(fee) || fee <= 0) return;
        double amount = add(fee, 0); // 6 decimals: drops float noise like 0.7999999999999972
        if (!(amount > 0)) return;
        owed = add(owed, amount);
        flush();
    }

    /** Tries to deposit what is owed. Called by {@link #credit} and on startup. */
    public synchronized void flush() {
        if (needsReview()) {
            if (!storageUnavailable) save();
            return;
        }
        if (!enabled() || !(owed > 0)) {
            save();
            return;
        }
        double amount = floor(owed, gateway.fractionalDigits());
        if (!(amount > 0)) {
            save();
            return;
        }
        pendingCredit = amount;
        if (!save()) {
            pendingCredit = 0;
            return;
        }
        boolean ok;
        try {
            ok = gateway.deposit(gateway.account(), amount);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Treasury deposit needs review. It will not be retried automatically.", e);
            return;
        }
        if (ok) {
            owed = add(owed, -amount);
            credited = add(credited, amount);
        }
        pendingCredit = 0;
        save();
    }

    /** Null-safe entry point for the trade services (tests often run without a treasury). */
    public static void credit(TreasuryService treasury, double fee) {
        if (treasury == null) return;
        try {
            treasury.credit(fee);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Treasury credit failed; the trade itself is complete.", e);
        }
    }

    /** Rounds an amount down to what the economy can actually move (0-digit gold: 2.91 -> 2). */
    public synchronized double toEconomy(double amount) {
        return floor(amount, gateway.fractionalDigits());
    }

    /** True when the treasury account holds at least {@code amount} beyond what Stonks still owes it. */
    public synchronized boolean canPay(double amount) {
        if (!enabled() || needsReview() || !Double.isFinite(amount) || amount < 0) return false;
        double balance;
        try {
            balance = gateway.balance(gateway.account());
        } catch (RuntimeException e) {
            return false;
        }
        return Double.isFinite(balance) && balance >= amount;
    }

    /**
     * Records a backed trade before ExcellentShop runs and reserves treasury funds for a sale.
     */
    public synchronized boolean beginShop(ShopSettlement settlement) {
        if (!enabled() || needsReview() || !gateway.account().equals(settlement.account())
                || !Double.isFinite(settlement.amount()) || settlement.amount() <= 0
                || !Double.isFinite(settlement.before()) || settlement.before() < 0) return false;
        pendingShop = settlement;
        if (!save()) return false;
        if ("BUY".equals(settlement.direction())) return true;
        if (pay(settlement.amount())) return true;
        if (pendingPayout == 0 && !storageUnavailable) {
            pendingShop = null;
            save();
        }
        return false;
    }

    private boolean pay(double amount) {
        pendingPayout = amount;
        if (!save()) {
            pendingPayout = 0;
            return false;
        }
        boolean ok;
        try {
            ok = gateway.withdraw(gateway.account(), amount);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Treasury withdrawal needs review. Server shop payouts are paused.", e);
            return false;
        }
        if (ok) {
            paid = add(paid, amount);
        }
        pendingPayout = 0;
        return save() && ok;
    }

    /**
     * Finalizes only an observed debit/credit or a cancellation with an unchanged balance.
     * A failed observation deliberately leaves the persisted operation for manual review.
     */
    public synchronized boolean finishShop(ShopSettlement settlement, boolean cancelled, double after) {
        if (pendingShop != settlement || storageUnavailable || pendingPayout != 0
                || !settlement.account().equals(gateway.account()) || !Double.isFinite(after) || after < 0) {
            return false;
        }
        boolean purchase = "BUY".equals(settlement.direction());
        double expectedDelta = cancelled ? 0 : purchase ? -settlement.amount() : settlement.amount();
        if (Double.compare(add(settlement.before(), expectedDelta), add(after, 0)) != 0) return false;
        // A cancelled sale returns the already debited treasury funds through the existing owed ledger.
        if ((!cancelled && purchase) || (cancelled && !purchase)) {
            owed = add(owed, settlement.amount());
        }
        pendingShop = null;
        if (!save()) return false;
        if (owed > 0) flush();
        return true;
    }

    public synchronized String account() {
        return gateway.account();
    }

    public synchronized boolean needsReview() {
        return storageUnavailable || pendingCredit != 0 || pendingPayout != 0 || pendingShop != null;
    }

    public synchronized double paid() {
        return paid;
    }

    public synchronized double unbacked() {
        return unbacked;
    }

    public synchronized double credited() {
        return credited;
    }

    public synchronized double owed() {
        return owed;
    }

    /** Rounds down to the currency's digits (6 decimals when unlimited, to absorb float noise). */
    static double floor(double value, int digits) {
        if (!Double.isFinite(value) || value <= 0) return 0;
        int scale = digits < 0 ? 6 : digits;
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.DOWN).doubleValue();
    }

    /** Adds in decimal, so 40 + 1.2 + 0.8 is exactly 42 and no unit is lost when rounding down later. */
    static double add(double a, double b) {
        return BigDecimal.valueOf(a).add(BigDecimal.valueOf(b)).setScale(6, RoundingMode.HALF_EVEN).doubleValue();
    }

    private void load() {
        if (!Files.isRegularFile(ledger)) return;
        try {
            Map<?, ?> root = YAML.readValue(ledger.toFile(), Map.class);
            if (root == null) return;
            credited = root.get("credited") instanceof Number n ? n.doubleValue() : 0;
            owed = root.get("owed") instanceof Number n ? n.doubleValue() : 0;
            paid = root.get("paid") instanceof Number n ? n.doubleValue() : 0;
            unbacked = root.get("unbacked") instanceof Number n ? n.doubleValue() : 0;
            pendingCredit = root.get("pendingCredit") instanceof Number n ? n.doubleValue() : 0;
            pendingPayout = root.get("pendingPayout") instanceof Number n ? n.doubleValue() : 0;
            if (root.get("pendingShop") != null) {
                pendingShop = YAML.convertValue(root.get("pendingShop"), ShopSettlement.class);
            }
            if (needsReview()) LOG.severe("Treasury has a pending Vault operation; inspect the ledger before resuming it.");
        } catch (IOException | RuntimeException e) {
            storageUnavailable = true;
            LOG.log(Level.SEVERE, "Unable to read treasury ledger; treasury operations are paused.", e);
        }
    }

    private boolean save() {
        if (storageUnavailable) return false;
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("account", gateway.account() == null ? "" : gateway.account());
        root.put("credited", credited);
        root.put("owed", owed);
        root.put("paid", paid);
        root.put("unbacked", unbacked);
        root.put("pendingCredit", pendingCredit);
        root.put("pendingPayout", pendingPayout);
        if (pendingShop != null) root.put("pendingShop", pendingShop);
        Path temporary = null;
        try {
            Files.createDirectories(ledger.getParent());
            temporary = Files.createTempFile(ledger.getParent(), "ledger", ".tmp");
            Files.writeString(temporary, YAML.writeValueAsString(root), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, ledger, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, ledger, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
            return true;
        } catch (IOException e) {
            storageUnavailable = true;
            LOG.log(Level.SEVERE, "Unable to save treasury ledger " + ledger, e);
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }
}

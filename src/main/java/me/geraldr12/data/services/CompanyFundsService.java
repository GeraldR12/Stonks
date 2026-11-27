package me.geraldr12.data.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Each company's cash ({@code plugins/Stonks/funds/ledger.yml}). Gold enters only when a player paid it
 * (creation, share purchases, deposits, linked shop sales) and leaves only to a player or the treasury
 * (share sales, dividends, closure, bankruptcy). Stonks never pays out gold it did not take in.
 *
 * <p>Every payout is reserved first: the balance drops and the operation is saved as pending before Vault is
 * called. The caller then settles it (gold left) or releases it (definitely refused). A pending operation that is
 * neither, because Vault was ambiguous or the server stopped, blocks that company until staff resolves it.
 * Interrupted steps can strand gold but never duplicate it.</p>
 */
public class CompanyFundsService {
    private static final Logger LOG = Logger.getLogger("Stonks");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    static final int CREDIT_MEMORY = 500;

    public record Pending(String id, long companyId, String kind, double amount, String player, long createdAt) {
    }

    private final BooleanSupplier enabled;
    private final Path ledger;
    private final Map<Long, Double> balances = new TreeMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Deque<String> credits = new ArrayDeque<>();
    private double paidIn;
    private double paidOut;
    private boolean storageUnavailable;

    public CompanyFundsService(BooleanSupplier enabled, Path ledger) {
        this.enabled = enabled;
        this.ledger = ledger;
        load();
    }

    public synchronized boolean enabled() {
        try {
            return enabled.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public synchronized double balance(long companyId) {
        return balances.getOrDefault(companyId, 0.0);
    }

    /** Adds gold a player already paid. The same operation id never credits twice, even after a restart. */
    public synchronized boolean credit(long companyId, double amount, String operationId) {
        if (!enabled() || storageUnavailable || companyId < 0 || operationId == null || operationId.isBlank()
                || !Double.isFinite(amount) || amount <= 0) return false;
        if (credits.contains(operationId)) return true;
        double before = balance(companyId);
        balances.put(companyId, add(before, amount));
        credits.addLast(operationId);
        while (credits.size() > CREDIT_MEMORY) credits.removeFirst();
        paidIn = add(paidIn, amount);
        if (save()) return true;
        balances.put(companyId, before);
        credits.removeLastOccurrence(operationId);
        paidIn = add(paidIn, -amount);
        return false;
    }

    /** True when this operation id was already credited (so its gold must not be charged again). */
    public synchronized boolean hasCredited(String operationId) {
        return operationId != null && credits.contains(operationId);
    }

    /** Takes {@code amount} out of the balance into a pending operation. Refused when the company cannot cover it. */
    public synchronized boolean reserve(long companyId, double amount, String operationId, String kind, String player) {
        if (!enabled() || storageUnavailable || companyId < 0 || operationId == null || operationId.isBlank()
                || pending.containsKey(operationId) || !Double.isFinite(amount) || amount <= 0) return false;
        double before = balance(companyId);
        if (add(before, -amount) < 0) return false;
        balances.put(companyId, add(before, -amount));
        pending.put(operationId, new Pending(operationId, companyId, kind, add(amount, 0), player,
                System.currentTimeMillis()));
        if (save()) return true;
        balances.put(companyId, before);
        pending.remove(operationId);
        return false;
    }

    /**
     * Moves the whole balance into a pending operation and returns it (0 when there was nothing).
     * Used for closure and bankruptcy.
     */
    public synchronized double drain(long companyId, String operationId, String kind) {
        double amount = balance(companyId);
        if (!(amount > 0)) return 0.0;
        return reserve(companyId, amount, operationId, kind, null) ? amount : 0.0;
    }

    /** The reserved gold left the company (paid to a player or the treasury). */
    public synchronized boolean settle(String operationId) {
        if (storageUnavailable || operationId == null) return false;
        Pending done = pending.remove(operationId);
        if (done == null) return false;
        paidOut = add(paidOut, done.amount());
        if (save()) return true;
        pending.put(operationId, done);
        paidOut = add(paidOut, -done.amount());
        return false;
    }

    /** The payout was definitely refused, so the reserved gold goes back to the company. */
    public synchronized boolean release(String operationId) {
        if (storageUnavailable || operationId == null) return false;
        Pending done = pending.remove(operationId);
        if (done == null) return false;
        double before = balance(done.companyId());
        balances.put(done.companyId(), add(before, done.amount()));
        if (save()) return true;
        pending.put(operationId, done);
        balances.put(done.companyId(), before);
        return false;
    }

    /** Forgets a company whose balance is zero and has nothing pending. */
    public synchronized boolean remove(long companyId) {
        if (storageUnavailable || hasPending(companyId) || balance(companyId) != 0.0) return false;
        Double removed = balances.remove(companyId);
        if (removed == null || save()) return true;
        balances.put(companyId, removed);
        return false;
    }

    /** True when this company has an unresolved payout, or when the ledger cannot be trusted. */
    public synchronized boolean hasPending(long companyId) {
        return storageUnavailable || pending.values().stream().anyMatch(p -> p.companyId() == companyId);
    }

    public synchronized boolean needsReview() {
        return storageUnavailable || !pending.isEmpty();
    }

    public synchronized List<Pending> pending() {
        return new ArrayList<>(pending.values());
    }

    public synchronized Pending pending(String operationId) {
        return pending.get(operationId);
    }

    /** All gold Stonks currently holds for companies, including reserved payouts. */
    public synchronized double totalHeld() {
        double total = 0;
        for (double balance : balances.values()) total = add(total, balance);
        for (Pending p : pending.values()) total = add(total, p.amount());
        return total;
    }

    public synchronized double paidIn() {
        return paidIn;
    }

    public synchronized double paidOut() {
        return paidOut;
    }

    /** Rounds down to what the economy can move (0-digit gold: 2.9 -> 2). */
    public static double floor(double value, int digits) {
        return TreasuryService.floor(value, digits);
    }

    static double add(double a, double b) {
        return TreasuryService.add(a, b);
    }

    private void load() {
        if (!Files.isRegularFile(ledger)) return;
        try {
            Map<?, ?> root = YAML.readValue(ledger.toFile(), Map.class);
            if (root == null) return;
            if (root.get("balances") instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getValue() instanceof Number n)) throw new IOException("Invalid balance");
                    balances.put(Long.parseLong(String.valueOf(entry.getKey())), n.doubleValue());
                }
            }
            if (root.get("pending") instanceof List<?> list) {
                for (Object item : list) {
                    Pending p = YAML.convertValue(item, Pending.class);
                    if (p.id() == null || !Double.isFinite(p.amount()) || p.amount() <= 0) {
                        throw new IOException("Invalid pending operation");
                    }
                    pending.put(p.id(), p);
                }
            }
            if (root.get("credits") instanceof List<?> list) {
                for (Object id : list) if (id != null) credits.addLast(String.valueOf(id));
            }
            paidIn = root.get("paidIn") instanceof Number n ? n.doubleValue() : 0;
            paidOut = root.get("paidOut") instanceof Number n ? n.doubleValue() : 0;
            if (!pending.isEmpty()) {
                LOG.severe("Company funds have " + pending.size()
                        + " pending payout(s); those companies are paused until staff resolves them.");
            }
        } catch (IOException | RuntimeException e) {
            storageUnavailable = true;
            LOG.log(Level.SEVERE, "Unable to read company funds ledger; company payouts are paused.", e);
        }
    }

    private boolean save() {
        if (storageUnavailable) return false;
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Double> serialized = new LinkedHashMap<>();
        balances.forEach((id, balance) -> serialized.put(String.valueOf(id), balance));
        root.put("balances", serialized);
        root.put("pending", new ArrayList<>(pending.values()));
        root.put("credits", new ArrayList<>(credits));
        root.put("paidIn", paidIn);
        root.put("paidOut", paidOut);
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
            LOG.log(Level.SEVERE, "Unable to save company funds ledger " + ledger, e);
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

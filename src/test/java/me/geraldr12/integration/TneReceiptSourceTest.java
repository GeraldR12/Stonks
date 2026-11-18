package me.geraldr12.integration;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Fakes follow TNE-Paper 0.1.5.3 method names and return types (javap: CallbackManager, PostTransactionCallback,
 * TransactionResult, Receipt, TransactionParticipant, HoldingsEntry). */
public class TneReceiptSourceTest {
    private static final UUID GOLD = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID GEMS = UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa");
    private static final UUID PLAYER = UUID.fromString("ca74957c-181d-477b-b177-ddd63ad9fa45");
    private static final UUID TREASURY = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");

    public static class CallbackManager {
        final List<String> names = new ArrayList<>();
        final List<Function<?, Boolean>> consumers = new ArrayList<>();
        public void addConsumer(String name, Function<?, Boolean> consumer) {
            names.add(name);
            consumers.add(consumer);
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        Boolean fire(Object callback) { return (Boolean) ((Function) consumers.getFirst()).apply(callback); }
    }

    public record HoldingsEntry(UUID getCurrency, BigDecimal getAmount, String getRegion) { }

    public record Participant(UUID getId, List<HoldingsEntry> getStartingBalances,
                              List<HoldingsEntry> getEndingBalances) { }

    public record Receipt(UUID getId, String getSource, Participant getFrom, Participant getTo) { }

    public record TransactionResult(boolean isSuccessful, String getMessage, Optional<Receipt> getReceipt) { }

    public record PostTransactionCallback(TransactionResult result) {
        public String name() { return "transaction_post"; }
    }

    private static HoldingsEntry gold(String amount) { return new HoldingsEntry(GOLD, new BigDecimal(amount), "world"); }

    private static PostTransactionCallback withdraw(UUID receiptId, boolean ok, String from, String to) {
        Participant player = new Participant(PLAYER, List.of(gold(from)), List.of(gold(to)));
        return new PostTransactionCallback(new TransactionResult(ok, ok ? "ok" : "insufficient",
                Optional.of(new Receipt(receiptId, "Vault", player, null))));
    }

    private final List<TneReceiptSource.Receipt> delivered = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private boolean primary = true;

    private TneReceiptSource attached(CallbackManager manager) throws Exception {
        TneReceiptSource source = new TneReceiptSource(warnings::add, () -> primary);
        assertTrue(source.attach(manager, delivered::add));
        return source;
    }

    @Test
    void absentTneLeavesTheSourceInactiveWithoutThrowing() {
        TneReceiptSource source = new TneReceiptSource(warnings::add, () -> true);
        assertFalse(source.register(null, delivered::add));
        assertFalse(source.active());
    }

    @Test
    void registersOnTheTransactionPostCallback() throws Exception {
        CallbackManager manager = new CallbackManager();
        TneReceiptSource source = attached(manager);
        assertEquals(List.of("transaction_post"), manager.names);
        assertTrue(source.active());
    }

    @Test
    void convertsAWithdrawalIntoTheParticipantsDelta() throws Exception {
        CallbackManager manager = new CallbackManager();
        attached(manager);
        UUID id = UUID.randomUUID();
        assertEquals(Boolean.FALSE, manager.fire(withdraw(id, true, "500.00", "455.00")));
        assertEquals(1, delivered.size());
        TneReceiptSource.Receipt receipt = delivered.getFirst();
        assertEquals(id, receipt.receiptId());
        assertEquals(PLAYER, receipt.participant());
        assertTrue(receipt.successful());
        assertEquals(0, new BigDecimal("-45").compareTo(receipt.delta()));
        assertFalse(receipt.offThread());
        assertEquals("Vault", receipt.source());
    }

    @Test
    void failedTransactionIsStillDeliveredAsFailed() throws Exception {
        CallbackManager manager = new CallbackManager();
        attached(manager);
        manager.fire(withdraw(UUID.randomUUID(), false, "10", "10"));
        assertEquals(1, delivered.size());
        assertFalse(delivered.getFirst().successful());
        assertEquals(0, BigDecimal.ZERO.compareTo(delivered.getFirst().delta()));
    }

    @Test
    void transferGivesOneReceiptPerParticipant() throws Exception {
        CallbackManager manager = new CallbackManager();
        attached(manager);
        Participant from = new Participant(PLAYER, List.of(gold("50")), List.of(gold("40")));
        Participant to = new Participant(TREASURY, List.of(gold("0")), List.of(gold("10")));
        manager.fire(new PostTransactionCallback(new TransactionResult(true, "",
                Optional.of(new Receipt(UUID.randomUUID(), "Vault", from, to)))));
        assertEquals(List.of(PLAYER, TREASURY), delivered.stream().map(TneReceiptSource.Receipt::participant).toList());
        assertEquals(0, new BigDecimal("10").compareTo(delivered.get(1).delta()));
    }

    @Test
    void severalCurrenciesChangingGiveNoDelta() throws Exception {
        CallbackManager manager = new CallbackManager();
        attached(manager);
        Participant player = new Participant(PLAYER,
                List.of(gold("50"), new HoldingsEntry(GEMS, BigDecimal.ONE, "world")),
                List.of(gold("40"), new HoldingsEntry(GEMS, BigDecimal.TEN, "world")));
        manager.fire(new PostTransactionCallback(new TransactionResult(true, "",
                Optional.of(new Receipt(UUID.randomUUID(), "Vault", player, null)))));
        assertNull(delivered.getFirst().delta());
    }

    @Test
    void callbackOffTheServerThreadIsMarked() throws Exception {
        CallbackManager manager = new CallbackManager();
        attached(manager);
        primary = false;
        manager.fire(withdraw(UUID.randomUUID(), true, "10", "5"));
        assertTrue(delivered.getFirst().offThread());
    }

    @Test
    void consumerNeverThrowsAndNeverCancels() throws Exception {
        CallbackManager manager = new CallbackManager();
        TneReceiptSource source = new TneReceiptSource(warnings::add, () -> true);
        source.attach(manager, receipt -> { throw new IllegalStateException("bridge broke"); });
        assertEquals(Boolean.FALSE, manager.fire(withdraw(UUID.randomUUID(), true, "10", "5")));
        assertEquals(Boolean.FALSE, manager.fire(new Object()));
        assertEquals(Boolean.FALSE, manager.fire(null));
        assertEquals(Boolean.FALSE, manager.fire(new PostTransactionCallback(
                new TransactionResult(true, "", Optional.empty()))));
        assertEquals(2, warnings.size(), warnings::toString);
    }

    @Test
    void unregisterSilencesTheConsumer() throws Exception {
        CallbackManager manager = new CallbackManager();
        TneReceiptSource source = attached(manager);
        source.unregister();
        assertEquals(Boolean.FALSE, manager.fire(withdraw(UUID.randomUUID(), true, "10", "5")));
        assertTrue(delivered.isEmpty());
        assertFalse(source.active());
    }

    @Test
    void managerWithoutTheExpectedMethodLeavesTheSourceInactive() {
        TneReceiptSource source = new TneReceiptSource(warnings::add, () -> true);
        assertThrows(NoSuchMethodException.class, () -> source.attach(new Object(), delivered::add));
        assertFalse(source.active());
    }
}

package me.geraldr12.commands;

import me.geraldr12.Stonks;
import me.geraldr12.persistence.MarketTransactionEffect;
import me.geraldr12.persistence.MarketTransactionEffectState;
import me.geraldr12.persistence.MarketTransactionJournal;
import me.geraldr12.persistence.MarketTransactionRecord;
import me.geraldr12.persistence.ReconciliationAuditEntry;
import me.geraldr12.persistence.ReconciliationEvidence;
import me.geraldr12.persistence.ReconciliationEvidenceType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class ReconcileCommand implements MainCommand.SubCommand {

    private static final String RECONCILE_PERMISSION = "stonks.admin.reconcile";
    private static final String INVESTIGATE_PERMISSION = "stonks.admin.investigate";
    private static final int MAX_RECORDS = 25;
    private static final int MAX_ENTRIES = 20;
    private static final int MAX_INPUT_TEXT = 2048;
    private static final int MAX_DISPLAY_TEXT = 256;

    private final Stonks plugin;

    public ReconcileCommand(Stonks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        return execute(player, args);
    }

    public boolean execute(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("investigate")) {
            return investigate(sender, args);
        }
        if (!sender.hasPermission(RECONCILE_PERMISSION)) {
            sender.sendMessage("You do not have permission to reconcile transactions.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("Usage: /invest reconcile <list|show|resolve>");
            return true;
        }

        MarketTransactionJournal journal = plugin.getTransactionJournal();
        if (journal == null || !plugin.isJournalReady()) {
            sender.sendMessage("Transaction reconciliation is unavailable.");
            return true;
        }

        try {
            return switch (args[0].toLowerCase()) {
                case "list" -> list(sender, journal);
                case "show" -> show(sender, journal, args);
                case "resolve" -> resolve(sender, journal, args);
                default -> {
                    sender.sendMessage("Usage: /invest reconcile <list|show|resolve>");
                    yield true;
                }
            };
        } catch (IllegalStateException e) {
            sender.sendMessage("Unable to read transaction journal safely.");
            plugin.getLogger().severe("Reconciliation command failed: " + e.getMessage());
            return true;
        }
    }

    private boolean investigate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Investigation commands require an authenticated player.");
            return true;
        }
        if (!sender.hasPermission(INVESTIGATE_PERMISSION)) {
            sender.sendMessage("You do not have permission to investigate transactions.");
            return true;
        }
        MarketTransactionJournal journal = plugin.getTransactionJournal();
        if (journal == null || !plugin.isJournalReady()) {
            sender.sendMessage("Transaction investigation is unavailable.");
            return true;
        }
        try {
            if (args.length < 2) {
                sender.sendMessage("Usage: /invest reconcile investigate <list|inspect|evidence>");
                return true;
            }
            return switch (args[1].toLowerCase(Locale.ROOT)) {
                case "list" -> investigationList(sender, journal);
                case "inspect" -> investigationInspect(sender, journal, args);
                case "evidence" -> investigationEvidence((Player) sender, journal, args);
                default -> {
                    sender.sendMessage("Usage: /invest reconcile investigate <list|inspect|evidence>");
                    yield true;
                }
            };
        } catch (IllegalStateException e) {
            sender.sendMessage("Unable to read transaction journal safely.");
            plugin.getLogger().severe("Transaction investigation failed: " + e.getMessage());
            return true;
        }
    }

    private boolean investigationList(CommandSender sender, MarketTransactionJournal journal) {
        List<MarketTransactionRecord> records = journal.getUnresolvedRecords();
        if (records.isEmpty()) {
            sender.sendMessage("No unresolved transactions.");
            return true;
        }
        int limit = Math.min(records.size(), MAX_RECORDS);
        for (int i = 0; i < limit; i++) {
            MarketTransactionRecord record = records.get(i);
            sender.sendMessage("transaction=" + safe(record.getTransactionId())
                    + " operation=" + safe(record.getOperation())
                    + " status=" + record.getStatus()
                    + " blocking=" + journal.isBlocking(record)
                    + " evidence=" + record.getReconciliationEvidenceView().size());
        }
        if (records.size() > limit) {
            sender.sendMessage("Results truncated to " + MAX_RECORDS + " transactions.");
        }
        return true;
    }

    private boolean investigationInspect(CommandSender sender, MarketTransactionJournal journal, String[] args) {
        if (args.length != 3 || !validUuid(args[2])) {
            sender.sendMessage("Usage: /invest reconcile investigate inspect <transaction-id>");
            return true;
        }
        Optional<MarketTransactionRecord> found = journal.getRecord(args[2]);
        if (found.isEmpty()) {
            sender.sendMessage("Transaction not found.");
            return true;
        }
        MarketTransactionRecord record = found.get();
        sender.sendMessage("transaction=" + safe(record.getTransactionId())
                + " operation=" + safe(record.getOperation())
                + " status=" + record.getStatus()
                + " blocking=" + journal.isBlocking(record)
                + " evidence=" + record.getReconciliationEvidenceView().size());
        List<MarketTransactionEffect> effects = record.getEffects() == null ? List.of() : record.getEffects();
        effects.stream()
                .filter(effect -> effect.getState() == null || effect.getState() != MarketTransactionEffectState.CONFIRMED)
                .limit(MAX_ENTRIES)
                .forEach(effect -> sender.sendMessage("effect=" + safe(effect.getEffectId())
                        + " type=" + effect.getType() + " state=" + effect.getState()));
        sendEvidence(sender, record.getReconciliationEvidenceView());
        sendAudit(sender, record.getReconciliationAuditView());
        return true;
    }

    private boolean investigationEvidence(Player player, MarketTransactionJournal journal, String[] args) {
        if (args.length < 8) {
            player.sendMessage("Usage: /invest reconcile investigate evidence <transaction-id> <effect-id> <type> <external-reference> <idempotency-key> <statement...>");
            return true;
        }
        String transactionId = args[2];
        String effectId = args[3];
        String externalReference = args[5];
        String idempotencyKey = args[6];
        String statement = join(args, 7);
        if (!validUuid(transactionId) || !validUuid(effectId) || !validText(externalReference, 256)
                || !validText(idempotencyKey, 128) || !validText(statement, MAX_INPUT_TEXT)) {
            player.sendMessage("Invalid investigation evidence.");
            return true;
        }
        ReconciliationEvidenceType type;
        try {
            type = ReconciliationEvidenceType.valueOf(args[4].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            player.sendMessage("Invalid investigation evidence type.");
            return true;
        }
        Optional<MarketTransactionRecord> found = journal.getRecord(transactionId);
        if (found.isEmpty() || found.get().getEffects() == null
                || found.get().getEffects().stream().noneMatch(effect -> effectId.equals(effect.getEffectId()))) {
            player.sendMessage("Transaction or effect not found.");
            return true;
        }
        ReconciliationEvidence existing = findEvidence(found.get(), idempotencyKey);
        ReconciliationEvidence candidate = evidence(transactionId, effectId, type, externalReference,
                player, statement, idempotencyKey, existing);
        MarketTransactionJournal.EvidenceAppendResult result = journal.appendEvidence(candidate);
        if (result == MarketTransactionJournal.EvidenceAppendResult.CONFLICT) {
            Optional<MarketTransactionRecord> reloaded = journal.getRecord(transactionId);
            ReconciliationEvidence concurrent = reloaded.map(record -> findEvidence(record, idempotencyKey)).orElse(null);
            if (concurrent != null) {
                candidate = evidence(transactionId, effectId, type, externalReference,
                        player, statement, idempotencyKey, concurrent);
                result = journal.appendEvidence(candidate);
            }
        }
        player.sendMessage(resultMessage(result));
        return true;
    }

    private ReconciliationEvidence evidence(String transactionId, String effectId,
                                            ReconciliationEvidenceType type, String externalReference,
                                            Player player, String statement, String idempotencyKey,
                                            ReconciliationEvidence existing) {
        if (existing != null) {
            return new ReconciliationEvidence(existing.getEvidenceId(), transactionId, effectId, type,
                    externalReference, player.getUniqueId().toString(), player.getName(), statement,
                    existing.getCreatedAt(), idempotencyKey);
        }
        return ReconciliationEvidence.create(transactionId, effectId, type, externalReference,
                player.getUniqueId().toString(), player.getName(), statement,
                System.currentTimeMillis(), idempotencyKey);
    }

    private void sendEvidence(CommandSender sender, List<ReconciliationEvidence> evidence) {
        int limit = Math.min(evidence.size(), MAX_ENTRIES);
        for (int i = 0; i < limit; i++) {
            ReconciliationEvidence item = evidence.get(i);
            sender.sendMessage("evidence type=" + item.getEvidenceType()
                    + " external=" + safe(item.getExternalReference())
                    + " operator=" + safe(item.getOperatorName())
                    + " operatorId=" + safe(item.getOperatorId())
                    + " request=" + safe(item.getIdempotencyKey())
                    + " timestamp=" + item.getCreatedAt());
        }
        if (evidence.size() > limit) sender.sendMessage("Evidence listing truncated to " + MAX_ENTRIES + ".");
    }

    private void sendAudit(CommandSender sender, List<ReconciliationAuditEntry> audit) {
        int limit = Math.min(audit.size(), MAX_ENTRIES);
        for (int i = 0; i < limit; i++) {
            ReconciliationAuditEntry item = audit.get(i);
            sender.sendMessage("audit action=" + safe(item.getAction())
                    + " operatorId=" + safe(item.getOperatorId())
                    + " request=" + safe(item.getRequestId())
                    + " timestamp=" + item.getTimestamp());
        }
        if (audit.size() > limit) sender.sendMessage("Audit listing truncated to " + MAX_ENTRIES + ".");
    }

    private ReconciliationEvidence findEvidence(MarketTransactionRecord record, String idempotencyKey) {
        return record.getReconciliationEvidenceView().stream()
                .filter(item -> idempotencyKey.equals(item.getIdempotencyKey()))
                .findFirst().orElse(null);
    }

    private String resultMessage(MarketTransactionJournal.EvidenceAppendResult result) {
        return switch (result) {
            case RECORDED -> "Investigation evidence RECORDED; it is not proof of financial settlement.";
            case ALREADY_RECORDED -> "Investigation evidence ALREADY_RECORDED; it is not proof of financial settlement.";
            case CONFLICT -> "Investigation evidence CONFLICT: the request identity has different evidence.";
            case REJECTED -> "Investigation evidence REJECTED by transaction eligibility or validation.";
            case PERSISTENCE_FAILED -> "Investigation evidence PERSISTENCE_FAILED: persistence could not be confirmed. Inspect the transaction before retrying.";
        };
    }

    private boolean list(CommandSender sender, MarketTransactionJournal journal) {
        List<MarketTransactionRecord> records = journal.getUnresolvedRecords();
        if (records.isEmpty()) {
            sender.sendMessage("No unresolved transactions.");
        } else {
            records.forEach(record -> sender.sendMessage(record.getTransactionId()
                    + " " + record.getOperation() + " " + record.getStatus()));
        }
        return true;
    }

    private boolean show(CommandSender sender, MarketTransactionJournal journal, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /invest reconcile show <transaction-id>");
            return true;
        }
        Optional<MarketTransactionRecord> record = journal.getRecord(args[1]);
        if (record.isEmpty()) {
            sender.sendMessage("Transaction not found.");
            return true;
        }
        sender.sendMessage(record.get().toString());
        return true;
    }

    private boolean resolve(CommandSender sender, MarketTransactionJournal journal, String[] args) {
        if (args.length < 3 || args[2].isBlank()
                || (!args[2].startsWith("acknowledge:") && !args[2].startsWith("manual-repair-confirmed:"))) {
            sender.sendMessage("Usage: /invest reconcile resolve <transaction-id> <acknowledge:details|manual-repair-confirmed:details>");
            return true;
        }
        Optional<MarketTransactionRecord> record = journal.getRecord(args[1]);
        boolean resolved = args[2].startsWith("manual-repair-confirmed:")
                ? record.isPresent() && journal.resolveAfterManualRepair(record.get(), args[2])
                : record.isPresent() && journal.acknowledge(record.get(), args[2]);
        if (!resolved) {
            sender.sendMessage("Transaction cannot be resolved or acknowledged.");
            return true;
        }
        plugin.getLogger().warning("Transaction " + args[1] + " disposition by "
                + sender.getName() + ": " + args[2]);
        sender.sendMessage(args[2].startsWith("manual-repair-confirmed:")
                ? "Transaction resolved after operator-confirmed external repair."
                : "Transaction acknowledged; it remains unresolved and no economic repair was performed.");
        return true;
    }

    private String join(String[] args, int start) {
        return java.util.Arrays.stream(args, start, args.length).collect(Collectors.joining(" "));
    }

    private boolean validUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    private boolean validText(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength
                && value.codePoints().noneMatch(this::isUnsafeFormatting);
    }

    private String safe(String value) {
        if (value == null) return "null";
        return value.length() <= MAX_DISPLAY_TEXT
                && value.codePoints().noneMatch(this::isUnsafeFormatting)
                ? value : "[redacted]";
    }

    private boolean isUnsafeFormatting(int codePoint) {
        return Character.isISOControl(codePoint)
                || codePoint == '§'
                || (codePoint >= 0x202A && codePoint <= 0x202E)
                || (codePoint >= 0x2066 && codePoint <= 0x2069)
                || codePoint == 0x200E
                || codePoint == 0x200F;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of("list", "show", "resolve", "investigate").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("investigate")) {
            return List.of("list", "inspect", "evidence").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}

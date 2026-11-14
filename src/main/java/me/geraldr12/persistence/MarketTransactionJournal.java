package me.geraldr12.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import me.geraldr12.data.sources.yml.YmlDataSourceImpl;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;

public class MarketTransactionJournal {

    public enum EvidenceAppendResult {
        RECORDED,
        ALREADY_RECORDED,
        CONFLICT,
        REJECTED,
        PERSISTENCE_FAILED
    }

    public enum VerificationObservationAppendResult {
        RECORDED,
        ALREADY_RECORDED,
        CONFLICT,
        REJECTED,
        PERSISTENCE_FAILED
    }

    public enum RecoveryProposalAppendResult {
        RECORDED,
        ALREADY_RECORDED,
        CONFLICT,
        REJECTED,
        PERSISTENCE_FAILED
    }

    public enum RecoveryProposalFreshness {
        CURRENT,
        NEEDS_REEVALUATION,
        EXPIRED,
        UNAVAILABLE
    }

    private static final String DIRECTORY = "transactions";
    private static final String ARCHIVE_DIRECTORY = "transactions" + File.separator + "archive";
    private static final int MAX_DIAGNOSTICS = 32;
    private static final int MAX_DIAGNOSTIC_LENGTH = 160;

    private static final ObjectMapper ARCHIVE_MAPPER = createArchiveMapper();

    private static final ConcurrentHashMap<Path, Object> JOURNAL_LOCKS = new ConcurrentHashMap<>();

    private final YmlDataSourceImpl<MarketTransactionRecord> dataSource;
    private final File pluginDataFolder;
    private final Object journalLock;
    private final IntPredicate saveFailurePolicy;
    private final IntPredicate deleteFailurePolicy;
    private final AtomicInteger saveAttempts = new AtomicInteger();
    private final AtomicInteger deleteAttempts = new AtomicInteger();

    private static ObjectMapper createArchiveMapper() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.findAndRegisterModules();
        return mapper;
    }

    public MarketTransactionJournal(File pluginDataFolder) {
        this(pluginDataFolder, ignored -> true);
    }

    MarketTransactionJournal(File pluginDataFolder, IntPredicate saveFailurePolicy) {
        this(pluginDataFolder, saveFailurePolicy, ignored -> true);
    }

    MarketTransactionJournal(File pluginDataFolder,
                             IntPredicate saveFailurePolicy,
                             IntPredicate deleteFailurePolicy) {
        this.pluginDataFolder = pluginDataFolder;
        this.dataSource = new YmlDataSourceImpl<>(pluginDataFolder);
        this.journalLock = JOURNAL_LOCKS.computeIfAbsent(
                pluginDataFolder.toPath().toAbsolutePath().normalize(), ignored -> new Object());
        this.saveFailurePolicy = saveFailurePolicy == null ? ignored -> true : saveFailurePolicy;
        this.deleteFailurePolicy = deleteFailurePolicy == null ? ignored -> true : deleteFailurePolicy;
    }

    public boolean isBlocking(MarketTransactionRecord record) {
        if (record == null || record.getStatus() == null) {
            return false;
        }
        if (record.isCurrentSchema()) {
            return record.getStatus().isBlocking();
        }
        return record.getStatus() != MarketTransactionStatus.COMPLETED
                && (record.getStatus().isBlocking()
                || record.getStatus() == MarketTransactionStatus.ABORTED
                || record.getStatus() == MarketTransactionStatus.MARKET_IMPACT_APPLIED);
    }

    public MarketTransactionRecord migrateLegacyRecord(MarketTransactionRecord record) {
        if (!isValidRecord(record) || record.isCurrentSchema() || record.getStatus() == null) {
            return null;
        }
        record.setSchemaVersion(MarketTransactionRecord.CURRENT_SCHEMA_VERSION);
        record.setLegacyMigrated(true);
        record.ensureEffects();
        switch (record.getStatus()) {
            case PREPARED, ABORTED, RECONCILIATION_REQUIRED -> {
                addLegacyEffect(record, MarketTransactionEffectType.ECONOMY_DEBIT,
                        MarketTransactionEffectState.UNKNOWN, 0);
                addLegacyEffect(record, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL,
                        MarketTransactionEffectState.UNKNOWN, 1);
                addLegacyEffect(record, MarketTransactionEffectType.PLAYER_SHARE_ADD,
                        MarketTransactionEffectState.UNKNOWN, 2);
                record.setStatus(MarketTransactionStatus.RECONCILIATION_REQUIRED);
            }
            case ECONOMY_APPLIED -> {
                addLegacyEffect(record, MarketTransactionEffectType.ECONOMY_DEBIT,
                        MarketTransactionEffectState.CONFIRMED, 0);
                addLegacyEffect(record, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL,
                        MarketTransactionEffectState.UNKNOWN, 1);
                addLegacyEffect(record, MarketTransactionEffectType.PLAYER_SHARE_ADD,
                        MarketTransactionEffectState.UNKNOWN, 2);
                record.setStatus(MarketTransactionStatus.RECONCILIATION_REQUIRED);
            }
            case STATE_COMMITTED -> {
                addLegacyEffect(record, MarketTransactionEffectType.ECONOMY_DEBIT,
                        MarketTransactionEffectState.CONFIRMED, 0);
                addLegacyEffect(record, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL,
                        MarketTransactionEffectState.CONFIRMED, 1);
                addLegacyEffect(record, MarketTransactionEffectType.PLAYER_SHARE_ADD,
                        MarketTransactionEffectState.CONFIRMED, 2);
                record.setStatus(MarketTransactionStatus.COMMITTED);
            }
            case MARKET_IMPACT_STARTED -> {
                migrateLegacyRecordWithCommittedState(record);
                addLegacyEffect(record, MarketTransactionEffectType.MARKET_IMPACT,
                        MarketTransactionEffectState.UNKNOWN, 3);
                record.setStatus(MarketTransactionStatus.COMMITTED);
            }
            case MARKET_IMPACT_APPLIED, COMPLETED -> {
                migrateLegacyRecordWithCommittedState(record);
                addLegacyEffect(record, MarketTransactionEffectType.MARKET_IMPACT,
                        MarketTransactionEffectState.CONFIRMED, 3);
                record.setStatus(record.getStatus() == MarketTransactionStatus.COMPLETED
                        ? MarketTransactionStatus.COMPLETED : MarketTransactionStatus.COMMITTED);
            }
            default -> throw new IllegalStateException("Unsupported legacy transaction status");
        }
        return record;
    }

    private void migrateLegacyRecordWithCommittedState(MarketTransactionRecord record) {
        addLegacyEffect(record, MarketTransactionEffectType.ECONOMY_DEBIT,
                MarketTransactionEffectState.CONFIRMED, 0);
        addLegacyEffect(record, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL,
                MarketTransactionEffectState.CONFIRMED, 1);
        addLegacyEffect(record, MarketTransactionEffectType.PLAYER_SHARE_ADD,
                MarketTransactionEffectState.CONFIRMED, 2);
    }

    private void addLegacyEffect(MarketTransactionRecord record,
                                 MarketTransactionEffectType type,
                                 MarketTransactionEffectState state,
                                 int sequence) {
        if (record.getEffects().stream().anyMatch(effect -> effect.getType() == type)) return;
        MarketTransactionEffect effect = MarketTransactionEffect.requested(
                record.getTransactionId(), type, sequence, record.getValue(), record.getQuantity());
        effect.setState(state);
        record.getEffects().add(effect);
    }

    public boolean recoverTerminalPendingRecords() {
        synchronized (journalLock) {
            for (MarketTransactionRecord record : loadPendingRecords()) {
                if (record.getStatus() != null && record.getStatus().isTerminal()
                        && !archive(record)) return false;
            }
            return escalateInterruptedRecords();
        }
    }

    /** Flags unfinished startup records for administrator review without replaying their effects. */
    public boolean escalateInterruptedRecords() {
        synchronized (journalLock) {
            for (MarketTransactionRecord record : loadPendingRecords()) {
                MarketTransactionStatus status = record.getStatus();
                if (status != null && !status.isTerminal()
                        && status != MarketTransactionStatus.RECONCILIATION_REQUIRED
                        && !markReconciliationRequired(record,
                        "Interrupted at status " + status + "; found pending at startup")) return false;
            }
            return true;
        }
    }

    public JournalIntegrityResult inspectIntegrity() {
        synchronized (journalLock) {
            File pendingDirectory = new File(pluginDataFolder, DIRECTORY);
            File archiveDirectory = new File(pluginDataFolder, ARCHIVE_DIRECTORY);
            return inspectDirectoryState(pendingDirectory, archiveDirectory);
        }
    }

    public boolean bootstrapDirectories() {
        synchronized (journalLock) {
            File pendingDirectory = new File(pluginDataFolder, DIRECTORY);
            File archiveDirectory = new File(pluginDataFolder, ARCHIVE_DIRECTORY);
            return (pendingDirectory.exists() || pendingDirectory.mkdirs())
                    && (archiveDirectory.exists() || archiveDirectory.mkdirs())
                    && pendingDirectory.isDirectory()
                    && archiveDirectory.isDirectory();
        }
    }

    private JournalIntegrityResult inspectDirectoryState(File pendingDirectory, File archiveDirectory) {
        Path pendingPath = pendingDirectory.toPath().toAbsolutePath().normalize();
        Path archivePath = archiveDirectory.toPath().toAbsolutePath().normalize();
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        if (Files.isSymbolicLink(pendingPath) || Files.isSymbolicLink(archivePath)) {
            diagnostics.add("transaction journal path is not a supported directory");
            return new JournalIntegrityResult(JournalIntegrityAssessment.INVALID,
                    diagnostics.values(), 0, 0);
        }
        if (!Files.exists(pendingPath, LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(archivePath, LinkOption.NOFOLLOW_LINKS)) {
            diagnostics.add("transaction journal directories are absent");
            return new JournalIntegrityResult(JournalIntegrityAssessment.BOOTSTRAP_REQUIRED,
                    diagnostics.values(), 0, 0);
        }
        if (Files.isSymbolicLink(pendingPath) || Files.isSymbolicLink(archivePath)
                || !Files.isDirectory(pendingPath, LinkOption.NOFOLLOW_LINKS)
                || (Files.exists(archivePath, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(archivePath, LinkOption.NOFOLLOW_LINKS))) {
            diagnostics.add("transaction journal path is not a supported directory");
            return new JournalIntegrityResult(JournalIntegrityAssessment.INVALID,
                    diagnostics.values(), 0, 0);
        }
        Path[] pendingFiles;
        Path[] archiveFiles;
        try (Stream<Path> pendingStream = Files.list(pendingPath);
             Stream<Path> archiveStream = Files.exists(archivePath, LinkOption.NOFOLLOW_LINKS)
                     ? Files.list(archivePath) : Stream.empty()) {
            pendingFiles = pendingStream.sorted().toArray(Path[]::new);
            archiveFiles = archiveStream.sorted().toArray(Path[]::new);
        } catch (IOException | RuntimeException exception) {
            diagnostics.add("transaction journal enumeration failed");
            return new JournalIntegrityResult(JournalIntegrityAssessment.INDETERMINATE,
                    diagnostics.values(), 0, 0);
        }
        Set<String> pendingIdentities = new HashSet<>();
        Set<String> archiveIdentities = new HashSet<>();
        Map<String, MarketTransactionRecord> pendingRecords = new HashMap<>();
        Map<String, MarketTransactionRecord> archiveRecords = new HashMap<>();
        int pendingCount = inspectFiles(pendingFiles, pendingPath, pendingIdentities, diagnostics, pendingRecords, true);
        int archiveCount = inspectFiles(archiveFiles, archivePath, archiveIdentities, diagnostics, archiveRecords, false);
        for (String transactionId : pendingRecords.keySet()) {
            MarketTransactionRecord archive = archiveRecords.get(transactionId);
            if (archive != null && (!archive.getStatus().isTerminal()
                    || !samePersistedRecord(pendingRecords.get(transactionId), archive))) {
                diagnostics.add("conflicting pending and archived transaction");
            }
        }
        Set<String> crossDirectoryIds = new HashSet<>(pendingRecords.keySet());
        crossDirectoryIds.retainAll(archiveRecords.keySet());
        for (String transactionId : crossDirectoryIds) {
            if (!samePersistedRecord(pendingRecords.get(transactionId), archiveRecords.get(transactionId))) {
                diagnostics.add("conflicting pending and archived transaction");
            }
        }
        JournalIntegrityAssessment assessment = diagnostics.hasErrors()
                ? JournalIntegrityAssessment.INVALID : JournalIntegrityAssessment.VALID;
        return new JournalIntegrityResult(assessment, diagnostics.values(), pendingCount, archiveCount);
    }

    private int inspectFiles(Path[] files, Path directory, Set<String> identities,
                             DiagnosticCollector diagnostics, Map<String, MarketTransactionRecord> records,
                             boolean pending) {
        int count = 0;
        for (Path file : files) {
            if (Files.isSymbolicLink(file) || !file.normalize().startsWith(directory)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                if (!(pending && file.getFileName().toString().equals("archive")
                        && Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS))) {
                    diagnostics.add("unsupported transaction journal entry");
                }
                continue;
            }
            String name = file.getFileName().toString();
            if (!name.endsWith(".yml") || name.length() <= 4
                    || !isValidTransactionId(name.substring(0, name.length() - 4))) {
                diagnostics.add("unsupported transaction journal filename");
                continue;
            }
            String transactionId = name.substring(0, name.length() - 4);
            MarketTransactionRecord record = dataSource.load(relativePath(file), MarketTransactionRecord.class);
            if (record == null) {
                diagnostics.add("unreadable transaction journal record");
                continue;
            }
            try {
                validateLoadedRecord(record, transactionId);
                if (!pending && !record.getStatus().isTerminal()) {
                    diagnostics.add("nonterminal archived transaction");
                }
                if (!identities.add(transactionId)) diagnostics.add("duplicate transaction identity");
                records.put(transactionId, record);
                count++;
            } catch (RuntimeException exception) {
                diagnostics.add("invalid transaction journal record");
            }
        }
        return count;
    }

    private String relativePath(Path file) {
        return pluginDataFolder.toPath().toAbsolutePath().normalize().relativize(file).toString();
    }

    private static final class DiagnosticCollector {
        private static final String SUPPRESSION_MARKER =
                "additional integrity diagnostics suppressed";
        private final List<String> values = new ArrayList<>();
        private boolean hasErrors;
        private boolean suppressed;

        void add(String message) {
            hasErrors = true;
            if (values.size() >= MAX_DIAGNOSTICS - 1) {
                suppressed = true;
                return;
            }
            values.add(sanitize(message));
        }

        boolean hasErrors() { return hasErrors; }

        List<String> values() {
            if (suppressed && !values.contains(SUPPRESSION_MARKER)) {
                values.add(SUPPRESSION_MARKER);
            }
            return List.copyOf(values);
        }

        private String sanitize(String message) {
            StringBuilder sanitized = new StringBuilder(MAX_DIAGNOSTIC_LENGTH);
            for (int i = 0; i < message.length()
                    && sanitized.length() < MAX_DIAGNOSTIC_LENGTH; i++) {
                char character = message.charAt(i);
                sanitized.append(Character.isISOControl(character) ? '?' : character);
            }
            return sanitized.toString();
        }
    }

    private boolean samePersistedRecord(MarketTransactionRecord left,
                                        MarketTransactionRecord right) {
        return Objects.equals(left, right);
    }

    public MarketTransactionRecord prepare(MarketTransactionRecord record) {
        synchronized (journalLock) {
            if (!isValidRecord(record) || record.getStatus() != null
                    || getRecord(record.getTransactionId()).isPresent()) return null;
            record.setStatus(MarketTransactionStatus.PREPARED);
            return persistPending(record) ? record : null;
        }
    }

    public MarketTransactionRecord prepareCurrentSchema(MarketTransactionRecord record) {
        synchronized (journalLock) {
            if (!isValidRecord(record) || record.getStatus() != null
                    || getRecord(record.getTransactionId()).isPresent()) return null;
            record.setSchemaVersion(MarketTransactionRecord.CURRENT_SCHEMA_VERSION);
            if (!validSchema3Quote(record)) return null;
            record.setLegacyMigrated(false);
            record.ensureEffects();
            record.setStatus(MarketTransactionStatus.PREPARED);
            return persistPending(record) ? record : null;
        }
    }

    public boolean requestEffect(MarketTransactionRecord record,
                                 MarketTransactionEffectType type,
                                 int sequence,
                                 double amount,
                                 int quantity) {
        synchronized (journalLock) {
            if (record == null || !isValidTransactionId(record.getTransactionId()) || type == null) return false;
            Optional<MarketTransactionRecord> loaded = getRecord(record.getTransactionId());
            if (loaded.isEmpty()) return false;
            MarketTransactionRecord latest = loaded.get();
            if (!isCurrentRecord(latest) || sequence != expectedSequence(latest.getOperation(), type)
                    || latest.getEffects().stream().anyMatch(effect -> effect.getType() == type)
                    || !validEffectPayload(type, amount, quantity)
                    || !payloadMatchesQuote(latest, type, amount, quantity)) return false;
            MarketTransactionEffect effect = MarketTransactionEffect.requested(
                    latest.getTransactionId(), type, sequence, amount, quantity);
            latest.getEffects().add(effect);
            if (latest.getStatus() == MarketTransactionStatus.PREPARED) {
                latest.setStatus(MarketTransactionStatus.IN_PROGRESS);
            }
            if (derivesCommitted(latest)) latest.setStatus(MarketTransactionStatus.COMMITTED);
            if (!persistPending(latest)) return false;
            copyFinancialState(latest, record);
            return true;
        }
    }

    public boolean recordEffectOutcome(MarketTransactionRecord record,
                                       String effectId,
                                       MarketTransactionEffectState state,
                                       String reason) {
        synchronized (journalLock) {
            if (record == null || !isValidTransactionId(record.getTransactionId()) || effectId == null || state == null
                    || state == MarketTransactionEffectState.REQUESTED
                    || state == MarketTransactionEffectState.UNKNOWN) return false;
            Optional<MarketTransactionRecord> loaded = getRecord(record.getTransactionId());
            if (loaded.isEmpty()) return false;
            MarketTransactionRecord latest = loaded.get();
            if (!isCurrentRecord(latest)) return false;
            MarketTransactionEffect effect = latest.getEffects().stream()
                    .filter(candidate -> effectId.equals(candidate.getEffectId()))
                    .findFirst().orElse(null);
            if (effect == null || effect.getState() != MarketTransactionEffectState.REQUESTED) return false;
            effect.setState(state);
            effect.setReason(reason);
            if (state == MarketTransactionEffectState.CONFIRMED) effect.setConfirmedAt(System.currentTimeMillis());
            if (derivesCommitted(latest)) latest.setStatus(MarketTransactionStatus.COMMITTED);
            if (!persistPending(latest)) {
                return false;
            }
            copyFinancialState(latest, record);
            return true;
        }
    }

    public boolean isCurrentRecord(MarketTransactionRecord record) {
        return isValidRecord(record) && record.isCurrentSchema();
    }

    public boolean isEffectNotAttempted(MarketTransactionRecord record,
                                         MarketTransactionEffectType type) {
        return isCurrentRecord(record) && !record.isLegacyMigrated() && type != null
                && record.getEffects().stream().noneMatch(effect -> effect.getType() == type);
    }

    public boolean derivesCommitted(MarketTransactionRecord record) {
        if (!isCurrentRecord(record) || !schema3EffectsValid(record)) return false;
        if ("SELL".equals(record.getOperation())) {
            return effectState(record, MarketTransactionEffectType.PLAYER_SHARE_REMOVAL)
                            == MarketTransactionEffectState.CONFIRMED
                    && effectState(record, MarketTransactionEffectType.COMPANY_SHARE_ADD)
                            == MarketTransactionEffectState.CONFIRMED
                    && effectState(record, MarketTransactionEffectType.ECONOMY_CREDIT)
                            == MarketTransactionEffectState.CONFIRMED;
        }
        if (!"BUY".equals(record.getOperation())) return false;
        return effectState(record, MarketTransactionEffectType.ECONOMY_DEBIT)
                        == MarketTransactionEffectState.CONFIRMED
                && effectState(record, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL)
                        == MarketTransactionEffectState.CONFIRMED
                && effectState(record, MarketTransactionEffectType.PLAYER_SHARE_ADD)
                        == MarketTransactionEffectState.CONFIRMED;
    }

    public boolean derivesCompleted(MarketTransactionRecord record) {
        if (!isCurrentRecord(record) || !schema3EffectsValid(record)
                || !derivesCommitted(record)
                || effectState(record, MarketTransactionEffectType.MARKET_IMPACT)
                != MarketTransactionEffectState.CONFIRMED) {
            return false;
        }
        if ("SELL".equals(record.getOperation())) {
            return record.getEffects().stream().allMatch(effect ->
                    effect.getType() == MarketTransactionEffectType.PLAYER_SHARE_REMOVAL
                            || effect.getType() == MarketTransactionEffectType.COMPANY_SHARE_ADD
                            || effect.getType() == MarketTransactionEffectType.ECONOMY_CREDIT
                            || effect.getType() == MarketTransactionEffectType.MARKET_IMPACT);
        }
        if (!"BUY".equals(record.getOperation())) return false;
        return record.getEffects().stream().allMatch(effect ->
                effect.getType() == MarketTransactionEffectType.ECONOMY_DEBIT
                        || effect.getType() == MarketTransactionEffectType.COMPANY_SHARE_REMOVAL
                        || effect.getType() == MarketTransactionEffectType.PLAYER_SHARE_ADD
                        || effect.getType() == MarketTransactionEffectType.MARKET_IMPACT);
    }

    private boolean schema3EffectsValid(MarketTransactionRecord record) {
        if (record == null || !record.isSchema3() || record.isLegacyMigrated()
                || !validSchema3Quote(record) || record.getEffects() == null) {
            return false;
        }
        java.util.Set<MarketTransactionEffectType> types =
                java.util.EnumSet.noneOf(MarketTransactionEffectType.class);
        java.util.Set<Integer> sequences = new java.util.HashSet<>();
        java.util.Set<String> effectIds = new java.util.HashSet<>();
        return record.getEffects().stream().allMatch(effect ->
                effect != null && effect.getEffectId() != null
                        && effectIds.add(effect.getEffectId())
                        && effect.getTransactionId() != null
                        && record.getTransactionId().equals(effect.getTransactionId())
                        && effect.getType() != null && types.add(effect.getType())
                        && sequences.add(effect.getSequence())
                        && effect.getSequence() == expectedSequence(record.getOperation(), effect.getType())
                        && effect.getState() != null
                        && effect.getState() != MarketTransactionEffectState.UNKNOWN
                        && validEffectPayload(effect.getType(), effect.getAmount(), effect.getQuantity())
                        && payloadMatchesQuote(record, effect.getType(), effect.getAmount(), effect.getQuantity()));
    }

    public boolean derivesCompensated(MarketTransactionRecord record) {
        if (!isCurrentRecord(record) || !record.isSchema3() || record.isLegacyMigrated()
                || !schema3EffectsValid(record)) return false;
        if ("SELL".equals(record.getOperation())) {
            MarketTransactionEffectState removal = effectState(record,
                    MarketTransactionEffectType.PLAYER_SHARE_REMOVAL);
            MarketTransactionEffectState add = effectState(record,
                    MarketTransactionEffectType.COMPANY_SHARE_ADD);
            MarketTransactionEffectState credit = effectState(record,
                    MarketTransactionEffectType.ECONOMY_CREDIT);
            MarketTransactionEffectState companyCompensation = effectState(record,
                    MarketTransactionEffectType.COMPANY_SHARE_REMOVAL);
            MarketTransactionEffectState playerCompensation = effectState(record,
                    MarketTransactionEffectType.PLAYER_SHARE_RESTORATION);
            if (removal == MarketTransactionEffectState.CONFIRMED
                    && add == MarketTransactionEffectState.DEFINITE_REJECTED
                    && credit == null
                    && companyCompensation == null
                    && playerCompensation == MarketTransactionEffectState.CONFIRMED) {
                return sellCompensationTypes(record, false);
            }
            return removal == MarketTransactionEffectState.CONFIRMED
                    && add == MarketTransactionEffectState.CONFIRMED
                    && credit == MarketTransactionEffectState.DEFINITE_REJECTED
                    && companyCompensation == MarketTransactionEffectState.CONFIRMED
                    && playerCompensation == MarketTransactionEffectState.CONFIRMED
                    && sellCompensationTypes(record, true);
        }
        if (!"BUY".equals(record.getOperation())) return false;
        MarketTransactionEffectState debit = effectState(record, MarketTransactionEffectType.ECONOMY_DEBIT);
        MarketTransactionEffectState removal = effectState(record, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL);
        MarketTransactionEffectState add = effectState(record, MarketTransactionEffectType.PLAYER_SHARE_ADD);
        MarketTransactionEffectState companyRestore = effectState(record, MarketTransactionEffectType.COMPANY_SHARE_RESTORATION);
        MarketTransactionEffectState playerRestore = effectState(record, MarketTransactionEffectType.PLAYER_SHARE_RESTORATION);
        MarketTransactionEffectState refund = effectState(record, MarketTransactionEffectType.ECONOMY_REFUND);
        if (debit != MarketTransactionEffectState.CONFIRMED
                || refund != MarketTransactionEffectState.CONFIRMED
                || playerRestore != null) return false;
        boolean caseA = removal == MarketTransactionEffectState.DEFINITE_REJECTED
                && add == null && companyRestore == null
                && exactCompensationTypes(record, false)
                && refundMatchesDebit(record);
        boolean caseB = removal == MarketTransactionEffectState.CONFIRMED
                && add == MarketTransactionEffectState.DEFINITE_REJECTED
                && companyRestore == MarketTransactionEffectState.CONFIRMED
                && exactCompensationTypes(record, true)
                && refundMatchesDebit(record);
        return caseA || caseB;
    }

    private boolean sellCompensationTypes(MarketTransactionRecord record, boolean creditRejected) {
        return record.getEffects().stream().allMatch(effect -> {
            MarketTransactionEffectType type = effect.getType();
            return type == MarketTransactionEffectType.PLAYER_SHARE_REMOVAL
                    || type == MarketTransactionEffectType.COMPANY_SHARE_ADD
                    || type == MarketTransactionEffectType.ECONOMY_CREDIT
                    || type == MarketTransactionEffectType.PLAYER_SHARE_RESTORATION
                    || (creditRejected && type == MarketTransactionEffectType.COMPANY_SHARE_REMOVAL);
        });
    }

    private boolean refundMatchesDebit(MarketTransactionRecord record) {
        MarketTransactionEffect debit = effect(record, MarketTransactionEffectType.ECONOMY_DEBIT);
        MarketTransactionEffect refund = effect(record, MarketTransactionEffectType.ECONOMY_REFUND);
        return debit != null && refund != null
                && Double.compare(debit.getAmount(), refund.getAmount()) == 0
                && record.getQuoteTotalValue() != null
                && Double.compare(debit.getAmount(), record.getQuoteTotalValue()) == 0
                && debit.getQuantity() == refund.getQuantity()
                && debit.getQuantity() == record.getQuoteQuantity();
    }

    private MarketTransactionEffect effect(MarketTransactionRecord record, MarketTransactionEffectType type) {
        return record.getEffects().stream().filter(candidate -> candidate.getType() == type).findFirst().orElse(null);
    }

    private boolean exactCompensationTypes(MarketTransactionRecord record, boolean caseB) {
        return record.getEffects().stream().allMatch(effect -> {
            MarketTransactionEffectType type = effect.getType();
            return type == MarketTransactionEffectType.ECONOMY_DEBIT
                    || type == MarketTransactionEffectType.COMPANY_SHARE_REMOVAL
                    || type == MarketTransactionEffectType.ECONOMY_REFUND
                    || (caseB && (type == MarketTransactionEffectType.PLAYER_SHARE_ADD
                    || type == MarketTransactionEffectType.COMPANY_SHARE_RESTORATION));
        });
    }

    public boolean hasMarketImpactAttempted(MarketTransactionRecord record) {
        MarketTransactionEffectState state = effectState(record,
                MarketTransactionEffectType.MARKET_IMPACT);
        return state == MarketTransactionEffectState.REQUESTED
                || state == MarketTransactionEffectState.CONFIRMED
                || state == MarketTransactionEffectState.DEFINITE_REJECTED
                || state == MarketTransactionEffectState.AMBIGUOUS
                || state == MarketTransactionEffectState.UNKNOWN;
    }

    public boolean companyStateSafeForRefund(MarketTransactionRecord record) {
        return isCurrentRecord(record)
                && (effectState(record, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL)
                == MarketTransactionEffectState.DEFINITE_REJECTED
                || effectState(record, MarketTransactionEffectType.COMPANY_SHARE_RESTORATION)
                == MarketTransactionEffectState.CONFIRMED
                || isEffectNotAttempted(record, MarketTransactionEffectType.COMPANY_SHARE_REMOVAL));
    }

    public boolean playerStateSafeForRefund(MarketTransactionRecord record) {
        if (!isCurrentRecord(record)) return false;
        MarketTransactionEffectState add = effectState(record, MarketTransactionEffectType.PLAYER_SHARE_ADD);
        return add == MarketTransactionEffectState.DEFINITE_REJECTED
                || (add == null && isEffectNotAttempted(record, MarketTransactionEffectType.PLAYER_SHARE_ADD))
                || effectState(record, MarketTransactionEffectType.PLAYER_SHARE_RESTORATION)
                == MarketTransactionEffectState.CONFIRMED;
    }

    private MarketTransactionEffectState effectState(MarketTransactionRecord record,
                                                       MarketTransactionEffectType type) {
        return record.getEffects().stream()
                .filter(effect -> effect.getType() == type)
                .map(MarketTransactionEffect::getState)
                .findFirst().orElse(null);
    }

    public boolean markEconomyApplied(MarketTransactionRecord record) {
        return transition(record, MarketTransactionStatus.PREPARED,
                MarketTransactionStatus.ECONOMY_APPLIED, null);
    }

    public boolean markStateCommitted(MarketTransactionRecord record) {
        return transition(record, MarketTransactionStatus.ECONOMY_APPLIED,
                MarketTransactionStatus.STATE_COMMITTED, null);
    }

    public boolean markMarketImpactStarted(MarketTransactionRecord record) {
        return transition(record, MarketTransactionStatus.STATE_COMMITTED,
                MarketTransactionStatus.MARKET_IMPACT_STARTED, null);
    }

    public boolean markMarketImpactApplied(MarketTransactionRecord record) {
        return transition(record, MarketTransactionStatus.MARKET_IMPACT_STARTED,
                MarketTransactionStatus.MARKET_IMPACT_APPLIED, null);
    }

    public boolean saveCompensated(MarketTransactionRecord record) {
        if (!derivesCompensated(record)) return false;
        return saveStatus(record, MarketTransactionStatus.COMPENSATED, "compensated");
    }

    public boolean complete(MarketTransactionRecord record) {
        synchronized (journalLock) {
            if (record == null || !isValidTransactionId(record.getTransactionId())) return false;
            Optional<MarketTransactionRecord> loaded = getRecord(record.getTransactionId());
            if (loaded.isEmpty()) return false;
            MarketTransactionRecord latest = loaded.get();
            if (latest.getStatus() == MarketTransactionStatus.COMPLETED) {
                if (!archiveUnlocked(latest)) return false;
                copyFinancialState(latest, record);
                return true;
            }
            if (latest.isSchema3() ? !derivesCompleted(latest)
                    : latest.getStatus() != MarketTransactionStatus.MARKET_IMPACT_APPLIED) {
                return false;
            }
            latest.setStatus(MarketTransactionStatus.COMPLETED);
            if (!persistPending(latest) || !archiveUnlocked(latest)) return false;
            copyFinancialState(latest, record);
            return true;
        }
    }

    public boolean abort(MarketTransactionRecord record, String reason) {
        synchronized (journalLock) {
            if (!hasReason(reason) || record == null || !isValidTransactionId(record.getTransactionId())) {
                return false;
            }
            Optional<MarketTransactionRecord> loaded = getRecord(record.getTransactionId());
            if (loaded.isEmpty()) return false;
            MarketTransactionRecord latest = loaded.get();
            if (!Objects.equals(latest.getEffects(), record.getEffects())
                    || (latest.isSchema3() ? !derivesAborted(latest)
                    : latest.getStatus() != MarketTransactionStatus.PREPARED)) {
                return false;
            }
            latest.setStatus(MarketTransactionStatus.ABORTED);
            latest.setFailureReason(reason.trim());
            if (!persistPending(latest) || !archiveUnlocked(latest)) return false;
            copyFinancialState(latest, record);
            return true;
        }
    }

    public boolean derivesAborted(MarketTransactionRecord record) {
        if (!schema3EffectsValid(record) || record.getEffects().size() != 1) return false;
        MarketTransactionEffect first = record.getEffects().get(0);
        if ("SELL".equals(record.getOperation())) {
            return first.getType() == MarketTransactionEffectType.PLAYER_SHARE_REMOVAL
                    && first.getSequence() == 10
                    && first.getState() == MarketTransactionEffectState.DEFINITE_REJECTED;
        }
        if (!"BUY".equals(record.getOperation())) return false;
        return first.getType() == MarketTransactionEffectType.ECONOMY_DEBIT
                && first.getSequence() == 10
                && first.getState() == MarketTransactionEffectState.DEFINITE_REJECTED;
    }

    public EvidenceAppendResult appendEvidence(ReconciliationEvidence evidence) {
        synchronized (journalLock) {
            return appendEvidenceLocked(evidence);
        }
    }

    public VerificationObservationAppendResult appendVerificationObservation(
            VerificationObservation observation) {
        synchronized (journalLock) {
            return appendVerificationObservationLocked(observation);
        }
    }

    public RecoveryProposalAppendResult appendRecoveryProposal(RecoveryProposal proposal) {
        synchronized (journalLock) {
            return appendRecoveryProposalLocked(proposal);
        }
    }

    public RecoveryProposalFreshness assessRecoveryProposalFreshness(RecoveryProposal proposal) {
        return assessRecoveryProposalFreshness(proposal, System.currentTimeMillis());
    }

    public RecoveryProposalFreshness assessRecoveryProposalFreshness(RecoveryProposal proposal,
                                                                      long assessmentTime) {
        if (assessmentTime <= 0 || !validRecoveryProposal(proposal)) return RecoveryProposalFreshness.UNAVAILABLE;
        Optional<MarketTransactionRecord> loaded = getRecord(proposal.getTransactionId());
        if (loaded.isEmpty()) return RecoveryProposalFreshness.UNAVAILABLE;
        MarketTransactionRecord record = loaded.get();
        RecoveryProposal persisted = record.getRecoveryProposalsView().stream()
                .filter(candidate -> proposal.getProposalId().equals(candidate.getProposalId()))
                .findFirst().orElse(null);
        if (persisted == null || !samePersistedRecoveryProposal(persisted, proposal)) {
            return RecoveryProposalFreshness.UNAVAILABLE;
        }
        if (proposal.getExpiresAt() < assessmentTime) return RecoveryProposalFreshness.EXPIRED;
        if (!isBlocking(record)) return RecoveryProposalFreshness.NEEDS_REEVALUATION;
        if (!proposal.getTransactionFingerprint().equals(financialStateFingerprint(record))) {
            return RecoveryProposalFreshness.NEEDS_REEVALUATION;
        }
        if (proposal.getObservationIds().size() != record.getVerificationObservationsView().size()
                || proposal.getEvidenceIds().size() != record.getReconciliationEvidenceView().size()
                || proposal.getObservationIds().stream().anyMatch(id -> record.getVerificationObservationsView().stream()
                .noneMatch(observation -> id.equals(observation.getObservationId())))
                || proposal.getEvidenceIds().stream().anyMatch(id -> record.getReconciliationEvidenceView().stream()
                .noneMatch(evidence -> id.equals(evidence.getEvidenceId())))) {
            return RecoveryProposalFreshness.NEEDS_REEVALUATION;
        }
        return RecoveryProposalFreshness.CURRENT;
    }

    public String financialStateFingerprint(MarketTransactionRecord record) {
        if (record == null || record.getEffects() == null
                || !Double.isFinite(record.getValue())
                || (record.getQuoteUnitSharePrice() != null && !Double.isFinite(record.getQuoteUnitSharePrice()))
                || (record.getQuoteTotalValue() != null && !Double.isFinite(record.getQuoteTotalValue()))) {
            throw new IllegalArgumentException("Invalid transaction record");
        }
        StringBuilder canonical = new StringBuilder();
        appendCanonical(canonical, record.getTransactionId());
        appendCanonical(canonical, record.getOperation());
        appendCanonical(canonical, record.getPlayerUuid());
        appendCanonical(canonical, record.getCompanyId());
        appendCanonical(canonical, record.getQuantity());
        appendCanonical(canonical, record.getValue());
        appendCanonical(canonical, record.getQuoteCompanyId());
        appendCanonical(canonical, record.getQuotePlayerUuid());
        appendCanonical(canonical, record.getQuoteQuantity());
        appendCanonical(canonical, record.getQuoteUnitSharePrice());
        appendCanonical(canonical, record.getQuoteTotalValue());
        appendCanonical(canonical, record.getQuoteTimestamp());
        appendCanonical(canonical, record.getTimestamp());
        appendCanonical(canonical, record.getStatus());
        appendCanonical(canonical, record.getFailureReason());
        appendCanonical(canonical, record.isLegacyMigrated());
        for (MarketTransactionEffect effect : record.getEffects()) {
            appendCanonical(canonical, effect.getEffectId());
            appendCanonical(canonical, effect.getTransactionId());
            appendCanonical(canonical, effect.getType());
            appendCanonical(canonical, effect.getState());
            appendCanonical(canonical, effect.getSequence());
            appendCanonical(canonical, effect.getRequestedAt());
            appendCanonical(canonical, effect.getConfirmedAt());
            appendCanonical(canonical, effect.getProvider());
            appendCanonical(canonical, effect.getProviderReference());
            appendCanonical(canonical, effect.getReason());
            appendCanonical(canonical, effect.getAmount());
            appendCanonical(canonical, effect.getQuantity());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void appendCanonical(StringBuilder target, Object value) {
        String text = value == null ? "<null>" : value.toString();
        target.append(text.length()).append(':').append(text).append('|');
    }

    private RecoveryProposalAppendResult appendRecoveryProposalLocked(RecoveryProposal proposal) {
        if (!validRecoveryProposal(proposal)) return RecoveryProposalAppendResult.REJECTED;
        Optional<MarketTransactionRecord> loaded = getRecord(proposal.getTransactionId());
        if (loaded.isEmpty()) return RecoveryProposalAppendResult.REJECTED;
        MarketTransactionRecord record = loaded.get();
        record.ensureEffects();
        record.ensureRecoveryProposals();
        if (!isBlocking(record) || proposal.getExpiresAt() < System.currentTimeMillis()
                || !proposal.getTransactionFingerprint().equals(financialStateFingerprint(record))) {
            return RecoveryProposalAppendResult.REJECTED;
        }
        if (proposal.getObservationIds().stream().anyMatch(id -> record.getVerificationObservationsView().stream()
                .noneMatch(observation -> id.equals(observation.getObservationId())))
                || proposal.getEvidenceIds().stream().anyMatch(id -> record.getReconciliationEvidenceView().stream()
                .noneMatch(evidence -> id.equals(evidence.getEvidenceId())))) {
            return RecoveryProposalAppendResult.REJECTED;
        }
        if (proposal.getTargetEffectIds().size() != proposal.getTargetEffectTypes().size()
                || proposal.getTargetEffectIds().isEmpty()
                || proposal.getTargetEffectIds().stream().distinct().count() != proposal.getTargetEffectIds().size()) {
            return RecoveryProposalAppendResult.REJECTED;
        }
        for (int i = 0; i < proposal.getTargetEffectIds().size(); i++) {
            String effectId = proposal.getTargetEffectIds().get(i);
            MarketTransactionEffectType type = proposal.getTargetEffectTypes().get(i);
            if (record.getEffects().stream().noneMatch(effect -> effectId.equals(effect.getEffectId())
                    && effect.getType() == type)) return RecoveryProposalAppendResult.REJECTED;
        }
        RecoveryProposal existing = record.getRecoveryProposalsView().stream()
                .filter(candidate -> proposal.getIdempotencyKey().equals(candidate.getIdempotencyKey()))
                .findFirst().orElse(null);
        if (existing != null) return sameRecoveryProposal(existing, proposal)
                ? RecoveryProposalAppendResult.ALREADY_RECORDED : RecoveryProposalAppendResult.CONFLICT;
        if (record.getRecoveryProposalsView().stream().anyMatch(candidate ->
                proposal.getProposalId().equals(candidate.getProposalId()))) return RecoveryProposalAppendResult.CONFLICT;
        record.appendRecoveryProposal(proposal);
        try {
            if (persistPending(record)) return RecoveryProposalAppendResult.RECORDED;
        } catch (RuntimeException ignored) {
            record.removeRecoveryProposal(proposal.getProposalId());
            return RecoveryProposalAppendResult.PERSISTENCE_FAILED;
        }
        record.removeRecoveryProposal(proposal.getProposalId());
        return RecoveryProposalAppendResult.PERSISTENCE_FAILED;
    }

    private boolean samePersistedRecoveryProposal(RecoveryProposal left, RecoveryProposal right) {
        return sameRecoveryProposal(left, right)
                && Objects.equals(left.getProposalId(), right.getProposalId())
                && left.getRecordedAt() == right.getRecordedAt();
    }

    private boolean sameRecoveryProposal(RecoveryProposal left, RecoveryProposal right) {
        return Objects.equals(left.getTransactionId(), right.getTransactionId())
                && Objects.equals(left.getIdempotencyKey(), right.getIdempotencyKey())
                && Objects.equals(left.getTargetEffectIds(), right.getTargetEffectIds())
                && Objects.equals(left.getTargetEffectTypes(), right.getTargetEffectTypes())
                && left.getDisposition() == right.getDisposition()
                && left.getStatus() == right.getStatus()
                && Objects.equals(left.getTransactionFingerprint(), right.getTransactionFingerprint())
                && Objects.equals(left.getObservationIds(), right.getObservationIds())
                && Objects.equals(left.getEvidenceIds(), right.getEvidenceIds())
                && Double.compare(left.getExpectedAmount(), right.getExpectedAmount()) == 0
                && left.getExpectedQuantity() == right.getExpectedQuantity()
                && Objects.equals(left.getProvenance(), right.getProvenance())
                && left.getProposedAt() == right.getProposedAt()
                && left.getExpiresAt() == right.getExpiresAt()
                && Objects.equals(left.getDiagnostic(), right.getDiagnostic());
    }

    private VerificationObservationAppendResult appendVerificationObservationLocked(
            VerificationObservation observation) {
        if (!validVerificationObservation(observation)) {
            return VerificationObservationAppendResult.REJECTED;
        }
        Optional<MarketTransactionRecord> loaded = getRecord(observation.getTransactionId());
        if (loaded.isEmpty()) return VerificationObservationAppendResult.REJECTED;
        MarketTransactionRecord record = loaded.get();
        record.ensureEffects();
        record.ensureVerificationObservations();
        if (!isBlocking(record)) return VerificationObservationAppendResult.REJECTED;
        Optional<MarketTransactionEffect> target = record.getEffects().stream()
                .filter(effect -> observation.getEffectId().equals(effect.getEffectId()))
                .findFirst();
        if (target.isEmpty() || target.get().getType() != observation.getExpectedEffectType()) {
            return VerificationObservationAppendResult.REJECTED;
        }
        VerificationObservation existing = record.getVerificationObservationsView().stream()
                .filter(candidate -> observation.getIdempotencyKey().equals(candidate.getIdempotencyKey()))
                .findFirst().orElse(null);
        if (existing != null) {
            return sameVerificationObservation(existing, observation)
                    ? VerificationObservationAppendResult.ALREADY_RECORDED
                    : VerificationObservationAppendResult.CONFLICT;
        }
        if (record.getVerificationObservationsView().stream().anyMatch(candidate ->
                observation.getObservationId().equals(candidate.getObservationId()))) {
            return VerificationObservationAppendResult.CONFLICT;
        }
        record.appendVerificationObservation(observation);
        try {
            if (persistPending(record)) return VerificationObservationAppendResult.RECORDED;
        } catch (RuntimeException ignored) {
            record.removeVerificationObservation(observation.getObservationId());
            return VerificationObservationAppendResult.PERSISTENCE_FAILED;
        }
        record.removeVerificationObservation(observation.getObservationId());
        return VerificationObservationAppendResult.PERSISTENCE_FAILED;
    }

    private EvidenceAppendResult appendEvidenceLocked(ReconciliationEvidence evidence) {
        if (!validEvidence(evidence)) return EvidenceAppendResult.REJECTED;
        Optional<MarketTransactionRecord> loaded = getRecord(evidence.getTransactionId());
        if (loaded.isEmpty()) return EvidenceAppendResult.REJECTED;
        MarketTransactionRecord record = loaded.get();
        record.ensureEffects();
        record.ensureReconciliationData();
        if (!isBlocking(record)
                || record.getEffects().stream().noneMatch(effect ->
                evidence.getEffectId().equals(effect.getEffectId())
                        && (effect.getState() == MarketTransactionEffectState.REQUESTED
                        || effect.getState() == MarketTransactionEffectState.AMBIGUOUS
                        || effect.getState() == MarketTransactionEffectState.UNKNOWN))) {
            return EvidenceAppendResult.REJECTED;
        }
        ReconciliationEvidence existing = record.getReconciliationEvidenceView().stream()
                .filter(candidate -> evidence.getIdempotencyKey().equals(candidate.getIdempotencyKey()))
                .findFirst().orElse(null);
        if (existing != null) {
            return sameEvidence(existing, evidence)
                    && existing.getEvidenceId().equals(evidence.getEvidenceId())
                    ? EvidenceAppendResult.ALREADY_RECORDED
                    : EvidenceAppendResult.CONFLICT;
        }
        if (record.getReconciliationEvidenceView().stream().anyMatch(candidate ->
                evidence.getEvidenceId().equals(candidate.getEvidenceId()))) {
            return EvidenceAppendResult.CONFLICT;
        }
        ReconciliationEvidence storedEvidence = copyEvidence(evidence);
        ReconciliationAuditEntry audit = ReconciliationAuditEntry.evidenceRecorded(storedEvidence);
        record.appendReconciliationEvidence(storedEvidence);
        record.appendReconciliationAudit(audit);
        if (persistPending(record)) return EvidenceAppendResult.RECORDED;
        record.removeReconciliationEvidence(storedEvidence.getEvidenceId());
        record.removeReconciliationAudit(audit.getAuditId());
        return EvidenceAppendResult.PERSISTENCE_FAILED;
    }

    public boolean markReconciliationRequired(
            MarketTransactionRecord record, String reason) {
        if (!hasReason(reason) || record == null || record.getStatus() == null
                || record.getStatus().isTerminal()) {
            return false;
        }
        return saveStatus(record, MarketTransactionStatus.RECONCILIATION_REQUIRED, reason);
    }

    public boolean acknowledge(MarketTransactionRecord record, String reason) {
        if (!hasReason(reason) || record == null
                || record.getStatus() != MarketTransactionStatus.RECONCILIATION_REQUIRED) {
            return false;
        }
        return saveStatus(record, MarketTransactionStatus.RECONCILIATION_REQUIRED,
                "Acknowledged without automatic repair: " + reason.trim());
    }

    public boolean resolveAfterManualRepair(MarketTransactionRecord record, String reason) {
        return false;
    }

    private boolean transition(MarketTransactionRecord record,
                               MarketTransactionStatus expected,
                               MarketTransactionStatus next,
                               String reason) {
        if (record == null || record.getStatus() != expected) {
            return false;
        }
        return saveStatus(record, next, reason);
    }

    private boolean saveStatus(MarketTransactionRecord record,
                               MarketTransactionStatus status,
                               String reason) {
        synchronized (journalLock) {
            if (record == null || !isValidTransactionId(record.getTransactionId())) return false;
            Optional<MarketTransactionRecord> loaded = getRecord(record.getTransactionId());
            if (loaded.isEmpty()) return false;
            MarketTransactionRecord latest = loaded.get();
            if (latest.getStatus() != record.getStatus()
                    && !(status == MarketTransactionStatus.RECONCILIATION_REQUIRED
                    && latest.getStatus() != null && !latest.getStatus().isTerminal())) return false;
            latest.setStatus(status);
            if (reason != null) latest.setFailureReason(reason.trim());
            if (!persistPending(latest)) return false;
            copyFinancialState(latest, record);
            return true;
        }
    }

    private boolean persistPending(MarketTransactionRecord record) {
        if (record == null || !isValidRecord(record) || !validateReconciliationData(record)) return false;
        long previousRevision = record.getStateRevision();
        if (previousRevision < 0 || previousRevision == Long.MAX_VALUE) return false;
        record.setStateRevision(previousRevision + 1);
        try {
            int attempt = saveAttempts.incrementAndGet();
            boolean saved = saveFailurePolicy.test(attempt)
                    && dataSource.save(getPath(record.getTransactionId()), record);
            if (!saved) record.setStateRevision(previousRevision);
            return saved;
        } catch (RuntimeException exception) {
            record.setStateRevision(previousRevision);
            throw exception;
        }
    }

    private void copyFinancialState(MarketTransactionRecord source, MarketTransactionRecord target) {
        target.setSchemaVersion(source.getSchemaVersion());
        target.setStateRevision(source.getStateRevision());
        target.setOperation(source.getOperation());
        target.setPlayerUuid(source.getPlayerUuid());
        target.setCompanyId(source.getCompanyId());
        target.setQuantity(source.getQuantity());
        target.setValue(source.getValue());
        target.setQuoteCompanyId(source.getQuoteCompanyId());
        target.setQuotePlayerUuid(source.getQuotePlayerUuid());
        target.setQuoteQuantity(source.getQuoteQuantity());
        target.setQuoteUnitSharePrice(source.getQuoteUnitSharePrice());
        target.setQuoteTotalValue(source.getQuoteTotalValue());
        target.setQuoteTimestamp(source.getQuoteTimestamp());
        target.setTimestamp(source.getTimestamp());
        target.setStatus(source.getStatus());
        target.setFailureReason(source.getFailureReason());
        target.setLegacyMigrated(source.isLegacyMigrated());
        target.setEffects(source.getEffects());
        target.replaceReconciliationData(source.getReconciliationEvidenceView(),
                source.getReconciliationAuditView());
    }

    private boolean archive(MarketTransactionRecord record) {
        synchronized (journalLock) {
            return archiveUnlocked(record);
        }
    }

    private boolean archiveUnlocked(MarketTransactionRecord record) {
        if (record == null || !isValidRecord(record) || !validateReconciliationData(record)) return false;
        String pendingPath = getPath(record.getTransactionId());
        String archivePath = ARCHIVE_DIRECTORY + File.separator
                + record.getTransactionId() + ".yml";
        if (new File(pluginDataFolder, archivePath).exists()) {
            MarketTransactionRecord archived = dataSource.load(archivePath, MarketTransactionRecord.class);
            if (archived == null || archived.getStatus() == null
                    || !archived.getStatus().isTerminal()
                    || !samePersistedRecord(record, archived)) return false;
        } else if (!createArchiveIfAbsent(archivePath, record)) {
            return false;
        }
        return deleteFailurePolicy.test(deleteAttempts.incrementAndGet())
                && dataSource.delete(pendingPath);
    }

    private boolean createArchiveIfAbsent(String archivePath,
                                          MarketTransactionRecord record) {
        try {
            Path archiveFile = new File(pluginDataFolder, archivePath).toPath();
            Path archiveRoot = new File(pluginDataFolder, ARCHIVE_DIRECTORY).toPath()
                    .toAbsolutePath().normalize();
            Path normalizedArchive = archiveFile.toAbsolutePath().normalize();
            Path transactionsRoot = new File(pluginDataFolder, DIRECTORY).toPath()
                    .toAbsolutePath().normalize();
            if (!normalizedArchive.startsWith(archiveRoot)
                    || !archiveRoot.startsWith(transactionsRoot)
                    || !Files.isDirectory(transactionsRoot, LinkOption.NOFOLLOW_LINKS)
                    || (Files.exists(archiveRoot, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(archiveRoot, LinkOption.NOFOLLOW_LINKS))
                    || Files.exists(normalizedArchive, LinkOption.NOFOLLOW_LINKS)) return false;
            Files.createDirectories(archiveRoot);
            if (!Files.isDirectory(archiveRoot, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(normalizedArchive, LinkOption.NOFOLLOW_LINKS)) return false;
            byte[] serialized = ARCHIVE_MAPPER.writeValueAsBytes(record);
            Files.write(normalizedArchive, serialized,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return true;
        } catch (FileAlreadyExistsException exception) {
            return false;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private String getPath(String transactionId) {
        if (!isValidTransactionId(transactionId)) {
            throw new IllegalArgumentException("Invalid transaction ID");
        }
        return DIRECTORY + File.separator + transactionId + ".yml";
    }

    public Optional<MarketTransactionRecord> getRecord(String transactionId) {
        if (!isValidTransactionId(transactionId)) {
            return Optional.empty();
        }
        MarketTransactionRecord record = dataSource.load(
                getPath(transactionId), MarketTransactionRecord.class);
        if (record == null) {
            return Optional.empty();
        }
        validateLoadedRecord(record, transactionId);
        return Optional.of(record);
    }

    public List<MarketTransactionRecord> getUnresolvedRecords() {
        return loadPendingRecords().stream()
                .filter(this::isBlocking)
                .collect(Collectors.toList());
    }

    public List<MarketTransactionRecord> getAllPendingOrReconciliationRecords() {
        return loadPendingRecords();
    }

    private List<MarketTransactionRecord> loadPendingRecords() {
        return dataSource.getAllIds(DIRECTORY).stream()
                .map(id -> {
                    if (!isValidTransactionId(id)) {
                        throw new IllegalStateException("Invalid transaction journal file: " + id);
                    }
                    return getRecord(id).orElseThrow(() ->
                            new IllegalStateException("Unreadable transaction journal: " + id));
                })
                .collect(Collectors.toList());
    }

    private void validateLoadedRecord(MarketTransactionRecord record,
                                      String expectedId) {
        if (record == null || !expectedId.equals(record.getTransactionId())
                || record.getStatus() == null) {
            throw new IllegalStateException("Malformed transaction record: " + expectedId);
        }
        record.ensureEffects();
        record.ensureReconciliationData();
        record.ensureVerificationObservations();
        record.ensureRecoveryProposals();
        if (record.getSchemaVersion() != null
                && record.getSchemaVersion() != MarketTransactionRecord.LEGACY_SCHEMA_VERSION
                && !record.isCurrentSchema()) {
            throw new IllegalStateException("Unsupported transaction schema: " + expectedId);
        }
        if (!isValidRecord(record) || !validateEffects(record)
                || !validateReconciliationData(record)
                || !validateVerificationObservations(record)
                || !validateRecoveryProposals(record)) {
            throw new IllegalStateException("Malformed transaction record: " + expectedId);
        }
    }

    private boolean validateReconciliationData(MarketTransactionRecord record) {
        java.util.Set<String> evidenceIds = new java.util.HashSet<>();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (ReconciliationEvidence evidence : record.getReconciliationEvidenceView()) {
            if (!validEvidence(evidence) || !record.getTransactionId().equals(evidence.getTransactionId())
                    || !evidenceIds.add(evidence.getEvidenceId())
                    || !keys.add(evidence.getIdempotencyKey())
                    || record.getEffects().stream().noneMatch(effect ->
                    evidence.getEffectId().equals(effect.getEffectId()))) return false;
        }
        java.util.Set<String> auditIds = new java.util.HashSet<>();
        java.util.Set<String> auditedEvidenceIds = new java.util.HashSet<>();
        for (ReconciliationAuditEntry audit : record.getReconciliationAuditView()) {
            if (audit == null || !isValidUuid(audit.getAuditId())
                    || !"EVIDENCE_RECORDED".equals(audit.getAction())
                    || !auditedEvidenceIds.add(audit.getEvidenceId())
                    || !record.getTransactionId().equals(audit.getTransactionId())
                    || !isValidUuid(audit.getEvidenceId())
                    || !hasBoundedText(audit.getAction(), 128)
                    || !isValidUuid(audit.getOperatorId())
                    || audit.getTimestamp() <= 0
                    || !hasBoundedText(audit.getRequestId(), 128)
                    || !auditIds.add(audit.getAuditId())
                    || record.getReconciliationEvidenceView().stream().noneMatch(evidence ->
                    audit.getEvidenceId().equals(evidence.getEvidenceId())
                            && audit.getOperatorId().equals(evidence.getOperatorId())
                            && audit.getRequestId().equals(evidence.getIdempotencyKey())
                            && audit.getTimestamp() == evidence.getCreatedAt())) return false;
        }
        return auditedEvidenceIds.size() == evidenceIds.size()
                && evidenceIds.equals(auditedEvidenceIds);
    }

    private boolean validateEffects(MarketTransactionRecord record) {
        java.util.Set<String> effectIds = new java.util.HashSet<>();
        java.util.Set<MarketTransactionEffectType> effectTypes =
                java.util.EnumSet.noneOf(MarketTransactionEffectType.class);
        java.util.Set<Integer> sequences = new java.util.HashSet<>();
        for (MarketTransactionEffect effect : record.getEffects()) {
            if (effect == null || effect.getEffectId() == null || effect.getEffectId().isBlank()
                    || !record.getTransactionId().equals(effect.getTransactionId())
                    || effect.getType() == null || effect.getState() == null
                    || !effectIds.add(effect.getEffectId())
                    || !effectTypes.add(effect.getType())
                    || effect.getSequence() < 0 || !sequences.add(effect.getSequence())) {
                return false;
            }
            if (effect.getState() == MarketTransactionEffectState.UNKNOWN
                    && !record.isLegacyMigrated()) {
                return false;
            }
            if (record.isSchema3() && !record.isLegacyMigrated()
                    && (!validEffectPayload(effect.getType(), effect.getAmount(), effect.getQuantity())
                    || !payloadMatchesQuote(record, effect.getType(), effect.getAmount(), effect.getQuantity())
                    || effect.getSequence() != expectedSequence(record.getOperation(), effect.getType()))) {
                return false;
            }
        }
        if (record.getStatus() == MarketTransactionStatus.COMMITTED
                && !derivesCommitted(record)) {
            return false;
        }
        if (record.isSchema3() && !record.isLegacyMigrated()
                && record.getStatus() == MarketTransactionStatus.COMPLETED
                && !derivesCompleted(record)) {
            return false;
        }
        if (record.isCurrentSchema() && record.isLegacyMigrated()
                && record.getStatus() == MarketTransactionStatus.COMMITTED
                && !hasMarketImpactAttempted(record)) {
            return false;
        }
        if (!record.isCurrentSchema() && record.getStatus() != MarketTransactionStatus.STATE_COMMITTED
                && record.getStatus() != MarketTransactionStatus.MARKET_IMPACT_STARTED
                && record.getStatus() != MarketTransactionStatus.MARKET_IMPACT_APPLIED
                && record.getStatus() != MarketTransactionStatus.COMPLETED) {
            return true;
        }
        if (record.getStatus() == MarketTransactionStatus.COMPENSATED) {
            if ("SELL".equals(record.getOperation())) {
                return derivesCompensated(record);
            }
            if (effectState(record, MarketTransactionEffectType.ECONOMY_DEBIT)
                    != MarketTransactionEffectState.CONFIRMED
                    || !companyStateSafeForRefund(record)
                    || !playerStateSafeForRefund(record)
                    || effectState(record, MarketTransactionEffectType.ECONOMY_REFUND)
                    != MarketTransactionEffectState.CONFIRMED) {
                return false;
            }
        }
        return true;
    }

    private boolean validSchema3Quote(MarketTransactionRecord record) {
        return ("BUY".equals(record.getOperation()) || "SELL".equals(record.getOperation()))
                && record.getQuoteCompanyId() != null && record.getQuoteCompanyId().equals(record.getCompanyId())
                && record.getQuotePlayerUuid() != null && record.getQuotePlayerUuid().equals(record.getPlayerUuid())
                && record.getQuoteQuantity() != null && record.getQuoteQuantity() > 0
                && record.getQuoteQuantity() == record.getQuantity()
                && record.getQuantity() > 0
                && record.getQuoteUnitSharePrice() != null && Double.isFinite(record.getQuoteUnitSharePrice())
                && record.getQuoteUnitSharePrice() > 0
                && record.getQuoteTotalValue() != null && Double.isFinite(record.getQuoteTotalValue())
                && record.getQuoteTotalValue() > 0 && Double.compare(record.getQuoteTotalValue(), record.getValue()) == 0
                && record.getQuoteTimestamp() != null && record.getQuoteTimestamp() > 0;
    }

    private boolean hasFinancialAmbiguity(MarketTransactionRecord record) {
        return record.getEffects().stream().anyMatch(effect ->
                (effect.getType() == MarketTransactionEffectType.ECONOMY_DEBIT
                        || effect.getType() == MarketTransactionEffectType.COMPANY_SHARE_REMOVAL
                        || effect.getType() == MarketTransactionEffectType.PLAYER_SHARE_ADD
                        || effect.getType() == MarketTransactionEffectType.COMPANY_SHARE_RESTORATION
                        || effect.getType() == MarketTransactionEffectType.PLAYER_SHARE_RESTORATION
                        || effect.getType() == MarketTransactionEffectType.ECONOMY_REFUND
                        || effect.getType() == MarketTransactionEffectType.PLAYER_SHARE_REMOVAL
                        || effect.getType() == MarketTransactionEffectType.COMPANY_SHARE_ADD
                        || effect.getType() == MarketTransactionEffectType.ECONOMY_CREDIT
                        || effect.getType() == MarketTransactionEffectType.MARKET_IMPACT)
                        && (effect.getState() == MarketTransactionEffectState.REQUESTED
                        || effect.getState() == MarketTransactionEffectState.AMBIGUOUS
                        || effect.getState() == MarketTransactionEffectState.UNKNOWN));
    }

    private int expectedSequence(String operation, MarketTransactionEffectType type) {
        if ("SELL".equals(operation)) {
            return switch (type) {
                case PLAYER_SHARE_REMOVAL -> 10;
                case COMPANY_SHARE_ADD -> 20;
                case ECONOMY_CREDIT -> 30;
                case COMPANY_SHARE_REMOVAL -> 40;
                case PLAYER_SHARE_RESTORATION -> 50;
                case MARKET_IMPACT -> 70;
                default -> -1;
            };
        }
        // Default to BUY semantics mapped legacy
        return switch (type) {
            case ECONOMY_DEBIT -> 10;
            case COMPANY_SHARE_REMOVAL -> 20;
            case PLAYER_SHARE_ADD -> 30;
            case COMPANY_SHARE_RESTORATION -> 40;
            case PLAYER_SHARE_RESTORATION -> 50;
            case ECONOMY_REFUND -> 60;
            case MARKET_IMPACT -> 70;
            default -> -1;
        };
    }

    private boolean payloadMatchesQuote(MarketTransactionRecord record,
                                         MarketTransactionEffectType type,
                                         double amount, int quantity) {
        if (!record.isSchema3()) return true;
        if (record.getQuoteQuantity() == null || quantity != record.getQuoteQuantity()) return false;
        if (type == MarketTransactionEffectType.ECONOMY_DEBIT
                || type == MarketTransactionEffectType.ECONOMY_REFUND
                || type == MarketTransactionEffectType.ECONOMY_CREDIT) {
            return record.getQuoteTotalValue() != null
                    && Double.compare(amount, record.getQuoteTotalValue()) == 0;
        }
        return Double.doubleToRawLongBits(amount) == Double.doubleToRawLongBits(0.0d);
    }

    private boolean validEffectPayload(MarketTransactionEffectType type, double amount, int quantity) {
        if (quantity <= 0) return false;
        if (type == MarketTransactionEffectType.ECONOMY_DEBIT
                || type == MarketTransactionEffectType.ECONOMY_REFUND
                || type == MarketTransactionEffectType.ECONOMY_CREDIT) {
            return Double.isFinite(amount) && amount > 0.0;
        }
        return Double.doubleToRawLongBits(amount) == Double.doubleToRawLongBits(0.0d);
    }

    private boolean isValidRecord(MarketTransactionRecord record) {
        return record != null
                && (!record.isSchema3() || validSchema3Quote(record))
                && record.getEffects() != null
                && isValidTransactionId(record.getTransactionId())
                && record.getOperation() != null
                && !record.getOperation().isBlank()
                && record.getPlayerUuid() != null
                && isValidUuid(record.getPlayerUuid())
                && record.getTimestamp() > 0
                && record.getStateRevision() >= 0
                && Double.isFinite(record.getValue())
                && record.getQuantity() >= 0;
    }

    private boolean isValidTransactionId(String transactionId) {
        return transactionId != null && isValidUuid(transactionId);
    }

    private boolean isValidUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    private boolean hasReason(String reason) {
        return reason != null && !reason.isBlank();
    }

    private boolean validateVerificationObservations(MarketTransactionRecord record) {
        java.util.Set<String> observationIds = new java.util.HashSet<>();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (VerificationObservation observation : record.getVerificationObservationsView()) {
            if (!validVerificationObservation(observation)
                    || !record.getTransactionId().equals(observation.getTransactionId())
                    || !observationIds.add(observation.getObservationId())
                    || !keys.add(observation.getIdempotencyKey())
                    || record.getEffects().stream().noneMatch(effect ->
                    observation.getEffectId().equals(effect.getEffectId())
                            && observation.getExpectedEffectType() == effect.getType())) return false;
        }
        return true;
    }

    private boolean validateRecoveryProposals(MarketTransactionRecord record) {
        java.util.Set<String> proposalIds = new java.util.HashSet<>();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (RecoveryProposal proposal : record.getRecoveryProposalsView()) {
            if (!validRecoveryProposal(proposal)
                    || !record.getTransactionId().equals(proposal.getTransactionId())
                    || !proposalIds.add(proposal.getProposalId())
                    || !keys.add(proposal.getIdempotencyKey())
                    || proposal.getTargetEffectIds().stream().distinct().count() != proposal.getTargetEffectIds().size()
                    || !proposal.getTargetEffectIds().stream().allMatch(effectId -> record.getEffects().stream()
                    .anyMatch(effect -> effectId.equals(effect.getEffectId())))
                    || !proposal.getTargetEffectIds().stream().allMatch(effectId -> {
                int index = proposal.getTargetEffectIds().indexOf(effectId);
                return record.getEffects().stream().anyMatch(effect -> effectId.equals(effect.getEffectId())
                        && effect.getType() == proposal.getTargetEffectTypes().get(index));
            })) return false;
        }
        return true;
    }

    private boolean validRecoveryProposal(RecoveryProposal proposal) {
        return proposal != null
                && isValidUuid(proposal.getProposalId())
                && isValidTransactionId(proposal.getTransactionId())
                && hasBoundedText(proposal.getIdempotencyKey(), 128)
                && proposal.getTargetEffectIds() != null
                && proposal.getTargetEffectTypes() != null
                && proposal.getTargetEffectIds().size() == proposal.getTargetEffectTypes().size()
                && !proposal.getTargetEffectIds().isEmpty()
                && proposal.getTargetEffectIds().stream().allMatch(this::isValidUuid)
                && proposal.getTargetEffectTypes().stream().allMatch(Objects::nonNull)
                && proposal.getDisposition() != null
                && proposal.getStatus() != null
                && proposal.getStatus() != RecoveryProposalStatus.CONFLICTED
                && hasBoundedText(proposal.getTransactionFingerprint(), 128)
                && Double.isFinite(proposal.getExpectedAmount())
                && proposal.getExpectedAmount() >= 0
                && proposal.getExpectedQuantity() >= 0
                && hasBoundedText(proposal.getProvenance(), 256)
                && proposal.getProposedAt() > 0
                && proposal.getRecordedAt() > 0
                && proposal.getExpiresAt() >= proposal.getProposedAt()
                && hasBoundedText(proposal.getDiagnostic(), 2048);
    }

    private boolean validVerificationObservation(VerificationObservation observation) {
        return observation != null
                && isValidUuid(observation.getObservationId())
                && isValidTransactionId(observation.getTransactionId())
                && isValidUuid(observation.getEffectId())
                && observation.getExpectedEffectType() != null
                && observation.getSource() != null
                && observation.getAssessment() != null
                && validObservationCombination(observation)
                && hasBoundedText(observation.getExternalReference(), 256)
                && hasBoundedText(observation.getProvenance(), 256)
                && observation.getObservationTimestamp() > 0
                && observation.getRecordedAt() > 0
                && hasBoundedText(observation.getIdempotencyKey(), 128)
                && hasBoundedText(observation.getDiagnostic(), 2048);
    }

    private boolean validObservationCombination(VerificationObservation observation) {
        return observation.getAssessment() == VerificationObservationAssessment.NOT_VERIFIABLE
                || observation.getSource() != VerificationObservationSource.UNAVAILABLE;
    }

    private boolean sameVerificationObservation(VerificationObservation left,
                                                VerificationObservation right) {
        return Objects.equals(left.getTransactionId(), right.getTransactionId())
                && Objects.equals(left.getEffectId(), right.getEffectId())
                && left.getExpectedEffectType() == right.getExpectedEffectType()
                && left.getSource() == right.getSource()
                && left.getAssessment() == right.getAssessment()
                && Objects.equals(left.getExternalReference(), right.getExternalReference())
                && Objects.equals(left.getProvenance(), right.getProvenance())
                && left.getObservationTimestamp() == right.getObservationTimestamp()
                && Objects.equals(left.getIdempotencyKey(), right.getIdempotencyKey())
                && Objects.equals(left.getDiagnostic(), right.getDiagnostic());
    }

    private boolean validEvidence(ReconciliationEvidence evidence) {
        return evidence != null
                && isValidUuid(evidence.getEvidenceId())
                && isValidTransactionId(evidence.getTransactionId())
                && isValidUuid(evidence.getEffectId())
                && evidence.getEvidenceType() != null
                && hasBoundedText(evidence.getExternalReference(), 256)
                && hasBoundedText(evidence.getOperatorId(), 128)
                && hasBoundedText(evidence.getOperatorName(), 128)
                && hasBoundedText(evidence.getStatement(), 2048)
                && evidence.getCreatedAt() > 0
                && hasBoundedText(evidence.getIdempotencyKey(), 128)
                && isValidUuid(evidence.getOperatorId());
    }

    private boolean hasBoundedText(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength
                && value.chars().noneMatch(Character::isISOControl);
    }

    private boolean sameEvidence(ReconciliationEvidence left, ReconciliationEvidence right) {
        return Objects.equals(left.getTransactionId(), right.getTransactionId())
                && Objects.equals(left.getEffectId(), right.getEffectId())
                && left.getEvidenceType() == right.getEvidenceType()
                && Objects.equals(left.getExternalReference(), right.getExternalReference())
                && Objects.equals(left.getOperatorId(), right.getOperatorId())
                && Objects.equals(left.getOperatorName(), right.getOperatorName())
                && Objects.equals(left.getStatement(), right.getStatement())
                && left.getCreatedAt() == right.getCreatedAt();
    }

    private ReconciliationAuditEntry copyAudit(ReconciliationAuditEntry source) {
        return new ReconciliationAuditEntry(source.getAuditId(), source.getTransactionId(),
                source.getEvidenceId(), source.getAction(), source.getOperatorId(),
                source.getTimestamp(), source.getRequestId());
    }

    private ReconciliationEvidence copyEvidence(ReconciliationEvidence source) {
        return new ReconciliationEvidence(source.getEvidenceId(), source.getTransactionId(),
                source.getEffectId(), source.getEvidenceType(), source.getExternalReference(),
                source.getOperatorId(), source.getOperatorName(), source.getStatement(),
                source.getCreatedAt(), source.getIdempotencyKey());
    }
}

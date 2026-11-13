package me.geraldr12.utils;

import me.geraldr12.Stonks;
import me.geraldr12.persistence.MarketTransactionJournal;
import java.util.UUID;
public final class MarketTransactionLocks {

    private static final int LOCK_STRIPES = 256;
    private static final Object[] COMPANY_LOCKS = new Object[LOCK_STRIPES];
    private static final Object[] PLAYER_LOCKS = new Object[LOCK_STRIPES];
    private static final Object ALLOCATION_LOCK = new Object();
    private static final Object LIFECYCLE_LOCK = new Object();
    private static volatile MarketTransactionJournal journal;
    private static volatile boolean initialized;
    private static LifecycleGeneration currentGeneration;
    private static long nextGenerationId;

    static {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            COMPANY_LOCKS[i] = new Object();
            PLAYER_LOCKS[i] = new Object();
        }
    }

    private MarketTransactionLocks() {
    }

    public static Object company(long companyId) {
        return COMPANY_LOCKS[Math.floorMod(Long.hashCode(companyId), LOCK_STRIPES)];
    }

    public static Object player(UUID playerId) {
        return PLAYER_LOCKS[Math.floorMod(playerId.hashCode(), LOCK_STRIPES)];
    }

    public static Object companyAllocation() {
        return ALLOCATION_LOCK;
    }

    public static void initialize(MarketTransactionJournal transactionJournal) {
        if (transactionJournal == null) {
            throw new IllegalArgumentException("Transaction journal is required");
        }
        transactionJournal.getUnresolvedRecords();
        synchronized (LIFECYCLE_LOCK) {
            if (currentGeneration != null && currentGeneration.activeAdmissions > 0) {
                throw new IllegalStateException("Active lifecycle admissions prevent journal replacement");
            }
            if (currentGeneration != null) {
                currentGeneration.state = LifecycleState.CLOSED;
            }
            LifecycleGeneration generation = new LifecycleGeneration(++nextGenerationId, transactionJournal);
            currentGeneration = generation;
            journal = transactionJournal;
            initialized = true;
        }
    }

    public static boolean activate(Stonks plugin, MarketTransactionJournal expectedJournal) {
        if (plugin == null || expectedJournal == null) {
            return false;
        }
        try {
            if (!plugin.isJournalReady()) {
                return false;
            }
        } catch (RuntimeException exception) {
            return false;
        }
        synchronized (LIFECYCLE_LOCK) {
            LifecycleGeneration generation = currentGeneration;
            if (generation == null || generation.journal != expectedJournal) {
                return false;
            }
            if (generation.state == LifecycleState.OPEN) {
                return true;
            }
            if (generation.state != LifecycleState.PENDING) {
                return false;
            }
            generation.state = LifecycleState.OPEN;
            return true;
        }
    }

    public static Admission tryAdmit(MarketTransactionJournal expectedJournal) {
        if (expectedJournal == null) {
            return null;
        }
        synchronized (LIFECYCLE_LOCK) {
            LifecycleGeneration generation = currentGeneration;
            if (generation == null
                    || generation.state != LifecycleState.OPEN
                    || generation.journal != expectedJournal) {
                return null;
            }
            generation.activeAdmissions++;
            return new Admission(generation);
        }
    }

    public static void revoke() {
        synchronized (LIFECYCLE_LOCK) {
            if (currentGeneration != null && currentGeneration.state == LifecycleState.OPEN) {
                currentGeneration.state = LifecycleState.CLOSING;
            } else if (currentGeneration != null && currentGeneration.state == LifecycleState.PENDING) {
                currentGeneration.state = LifecycleState.CLOSED;
            }
        }
    }

    public static long currentGeneration() {
        synchronized (LIFECYCLE_LOCK) {
            return currentGeneration == null ? 0L : currentGeneration.id;
        }
    }

    public static int activeAdmissionCount() {
        synchronized (LIFECYCLE_LOCK) {
            return currentGeneration == null ? 0 : currentGeneration.activeAdmissions;
        }
    }

    public static boolean isJournalLifecycleReady(Stonks plugin, MarketTransactionJournal currentJournal) {
        try {
            return plugin != null && currentJournal != null
                    && plugin.isJournalReady() && isOpenGeneration(currentJournal);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static boolean isFinanciallyReady(Stonks plugin, MarketTransactionJournal currentJournal,
                                             UUID playerId, long companyId) {
        try {
            if (plugin == null || currentJournal == null || playerId == null
                    || !plugin.isJournalReady() || !isOpenGeneration(currentJournal)) {
                return false;
            }
            return !requiresReconciliation(playerId, companyId);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static boolean isFinanciallyReady(Stonks plugin, MarketTransactionJournal currentJournal,
                                             UUID playerId) {
        try {
            if (plugin == null || currentJournal == null || playerId == null
                    || !plugin.isJournalReady() || !isOpenGeneration(currentJournal)) {
                return false;
            }
            return !requiresPlayerReconciliation(playerId);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isOpenGeneration(MarketTransactionJournal expectedJournal) {
        synchronized (LIFECYCLE_LOCK) {
            return initialized
                    && journal == expectedJournal
                    && currentGeneration != null
                    && currentGeneration.journal == expectedJournal
                    && currentGeneration.state == LifecycleState.OPEN;
        }
    }

    public static boolean requiresReconciliation(UUID playerId, long companyId) {
        MarketTransactionJournal currentJournal = journal;
        if (!initialized || currentJournal == null) {
            return false;
        }
        return currentJournal.getUnresolvedRecords().stream().anyMatch(record ->
                playerId.toString().equals(record.getPlayerUuid())
                        || Long.valueOf(companyId).equals(record.getCompanyId()));
    }

    public static boolean requiresPlayerReconciliation(UUID playerId) {
        MarketTransactionJournal currentJournal = journal;
        if (!initialized || currentJournal == null) {
            return false;
        }
        return currentJournal.getUnresolvedRecords().stream().anyMatch(record ->
                playerId.toString().equals(record.getPlayerUuid()));
    }

    private enum LifecycleState {
        PENDING,
        OPEN,
        CLOSING,
        CLOSED
    }

    private static final class LifecycleGeneration {
        private final long id;
        private final MarketTransactionJournal journal;
        private LifecycleState state = LifecycleState.PENDING;
        private int activeAdmissions;

        private LifecycleGeneration(long id, MarketTransactionJournal journal) {
            this.id = id;
            this.journal = journal;
        }
    }

    public static final class Admission implements AutoCloseable {
        private final LifecycleGeneration generation;
        private boolean released;

        private Admission(LifecycleGeneration generation) {
            this.generation = generation;
        }

        public long generation() {
            return generation.id;
        }

        public MarketTransactionJournal journal() {
            return generation.journal;
        }

        @Override
        public void close() {
            synchronized (LIFECYCLE_LOCK) {
                if (released) {
                    return;
                }
                released = true;
                if (generation.activeAdmissions > 0) {
                    generation.activeAdmissions--;
                }
                if (generation.activeAdmissions == 0
                        && generation.state == LifecycleState.CLOSING) {
                    generation.state = LifecycleState.CLOSED;
                }
            }
        }
    }
}

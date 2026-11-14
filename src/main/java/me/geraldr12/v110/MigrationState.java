package me.geraldr12.v110;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.time.Instant;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE)
public final class MigrationState {

    public enum Phase {
        BACKUP, STAGE, APPLY, VERIFY, ARCHIVE, DONE
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SCHEMA_VERSION = 1;

    private int version = SCHEMA_VERSION;
    private Phase phase = Phase.BACKUP;
    private String startedAt = Instant.now().toString();
    private String updatedAt = startedAt;
    private String failurePhase;
    private String failureReason;

    @JsonIgnore
    private transient File stateFile;
    @JsonIgnore
    private transient MigrationFileOps fileOps;

    private MigrationState() {
    }

    public static MigrationState load(File pluginDataDirectory, MigrationFileOps fileOps) {
        File file = new File(pluginDataDirectory, "migration/v110/state.json");
        MigrationState state = new MigrationState();
        if (file.exists()) {
            try {
                var tree = MAPPER.readTree(file);
                for (String field : new String[]{"version", "phase", "startedAt", "updatedAt"}) {
                    if (tree == null || !tree.hasNonNull(field)) {
                        throw new MigrationException("Migration state is invalid: " + file, null);
                    }
                }
                state = MAPPER.treeToValue(tree, MigrationState.class);
            } catch (IOException e) {
                throw new MigrationException("Migration state is unreadable: " + file, e);
            }
            if (state == null || state.phase == null || state.version != SCHEMA_VERSION) {
                throw new MigrationException("Migration state is invalid: " + file, null);
            }
        }
        state.stateFile = file;
        state.fileOps = fileOps;
        return state;
    }

    public Phase getPhase() {
        return phase;
    }

    public boolean isDone() {
        return phase == Phase.DONE;
    }

    public String getFailurePhase() {
        return failurePhase;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void advanceTo(Phase next) {
        Phase previous = phase;
        phase = next;
        if (next == Phase.DONE) {
            failurePhase = null;
            failureReason = null;
        }
        try {
            save();
        } catch (RuntimeException e) {
            phase = previous;
            throw e;
        }
    }

    public void recordFailure(String reason) {
        failurePhase = phase.name();
        failureReason = reason;
        save();
    }

    private void save() {
        updatedAt = Instant.now().toString();
        try {
            fileOps.writeAtomically(stateFile.toPath(), MAPPER.writeValueAsBytes(this));
        } catch (IOException e) {
            throw new MigrationException("Failed to save migration state", e);
        }
    }

    public static final class MigrationException extends RuntimeException {
        public MigrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

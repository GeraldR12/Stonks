package me.geraldr12.persistence;

import java.util.List;

public final class JournalIntegrityResult {
    private final JournalIntegrityAssessment assessment;
    private final List<String> diagnostics;
    private final int pendingRecordCount;
    private final int archivedRecordCount;

    public JournalIntegrityResult(JournalIntegrityAssessment assessment,
                                  List<String> diagnostics,
                                  int pendingRecordCount,
                                  int archivedRecordCount) {
        this.assessment = assessment;
        this.diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
        this.pendingRecordCount = pendingRecordCount;
        this.archivedRecordCount = archivedRecordCount;
    }

    public JournalIntegrityAssessment getAssessment() { return assessment; }
    public List<String> getDiagnostics() { return diagnostics; }
    public int getPendingRecordCount() { return pendingRecordCount; }
    public int getArchivedRecordCount() { return archivedRecordCount; }
}

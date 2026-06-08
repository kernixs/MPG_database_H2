package org.mpgdatabase.importer;

public record ClinicalDecisionImportResult(
        String fileName,
        boolean success,
        int recordsSeen,
        int classificationsInserted,
        int signedOutCallsInserted,
        int notesInserted
) {
}

package org.mpgdatabase.importer;

public record VcfImportResult(
        String fileName,
        boolean success,
        int recordsSeen,
        int variantsInsertedOrReused,
        int sampleCallsInserted,
        int annotationsInserted,
        int issuesInserted
) {
}

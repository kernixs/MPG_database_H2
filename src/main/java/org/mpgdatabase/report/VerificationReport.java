package org.mpgdatabase.report;

import org.mpgdatabase.importer.ImportResult;

import java.util.List;
import java.util.Map;

public record VerificationReport(
        boolean databaseInitializationPassed,
        Map<String, Long> tableCounts,
        List<ImportResult> importResults,
        VerificationResult schema,
        VerificationResult relationship,
        VerificationResult iscn,
        VerificationResult array,
        VerificationResult query,
        VerificationResult dataIntegrity
) {
    public boolean overallPassed() {
        return databaseInitializationPassed
                && importResults.stream().allMatch(ImportResult::success)
                && schema.passed()
                && relationship.passed()
                && iscn.passed()
                && array.passed()
                && query.passed()
                && dataIntegrity.passed();
    }
}

package org.mpgdatabase.importer;

import java.util.List;
import java.util.Map;

public record CnvRecord(
        int lineNumber,
        String sampleAccessionIdentifier,
        String eventGroupId,
        String chromosome,
        Long startPos,
        Long stopPos,
        String eventType,
        Integer copyNumber,
        Double arrayScore,
        String confidence,
        Integer numberOfSites,
        String genomeBuild,
        String rawIscn,
        String dnaSource,
        String annotationNames,
        String annotations,
        Map<String, String> sourceFields,
        String validationMessage,
        List<String> warningIssueTypes
) {
    public boolean validForSegment() {
        return validationMessage == null
                && sampleAccessionIdentifier != null
                && chromosome != null
                && startPos != null
                && stopPos != null
                && eventType != null
                && copyNumber != null;
    }

    public boolean hasRawIscn() {
        return rawIscn != null && !rawIscn.isBlank();
    }
}

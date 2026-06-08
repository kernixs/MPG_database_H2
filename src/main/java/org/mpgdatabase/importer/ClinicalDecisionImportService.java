package org.mpgdatabase.importer;

import org.mpgdatabase.dao.ClinicalDecisionDao;
import org.mpgdatabase.model.Models.Note;
import org.mpgdatabase.model.Models.SignedOutCall;
import org.mpgdatabase.model.Models.VariantClassification;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClinicalDecisionImportService {
    private final Connection connection;

    public ClinicalDecisionImportService(Connection connection) {
        this.connection = connection;
    }

    public ClinicalDecisionImportResult importFile(Path path) throws IOException, SQLException {
        List<String> lines = Files.readAllLines(path);
        int headerIndex = firstDataLine(lines);
        if (headerIndex < 0) {
            return new ClinicalDecisionImportResult(path.getFileName().toString(), false, 0, 0, 0, 0);
        }

        Map<String, Integer> header = header(lines.get(headerIndex));
        ClinicalDecisionDao dao = new ClinicalDecisionDao(connection);
        int recordsSeen = 0;
        int classificationsInserted = 0;
        int signedOutCallsInserted = 0;
        int notesInserted = 0;

        for (int i = headerIndex + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            recordsSeen++;
            String[] values = line.split("\t", -1);
            long segmentId = Long.parseLong(required(header, values, "segment_id"));
            SegmentContext context = segmentContext(segmentId);
            long classificationId = dao.createVariantClassification(new VariantClassification(
                    0,
                    segmentId,
                    required(header, values, "classification_label"),
                    optional(header, values, "guideline_system"),
                    optional(header, values, "guideline_version"),
                    optionalDouble(header, values, "evidence_score"),
                    optional(header, values, "evidence_summary"),
                    optional(header, values, "classified_by"),
                    valueOrDefault(optional(header, values, "review_status"), "Draft"),
                    true,
                    null));
            classificationsInserted++;

            long signedOutCallId = dao.createSignedOutCall(new SignedOutCall(
                    0,
                    segmentId,
                    classificationId,
                    context.individualId(),
                    context.sampleTestResultId(),
                    required(header, values, "clinical_significance"),
                    optional(header, values, "relevance_to_indication"),
                    optional(header, values, "interpretation_text"),
                    required(header, values, "signed_out_status"),
                    optional(header, values, "signed_out_by"),
                    optional(header, values, "report_text"),
                    optional(header, values, "report_version"),
                    null));
            signedOutCallsInserted++;

            String noteText = optional(header, values, "note_text");
            if (noteText != null && !noteText.isBlank()) {
                dao.createNote(new Note(
                        0,
                        "signed_out_calls",
                        signedOutCallId,
                        valueOrDefault(optional(header, values, "note_type"), "Reviewer note"),
                        noteText,
                        optional(header, values, "note_author")));
                notesInserted++;
            }
        }

        return new ClinicalDecisionImportResult(
                path.getFileName().toString(),
                true,
                recordsSeen,
                classificationsInserted,
                signedOutCallsInserted,
                notesInserted);
    }

    private int firstDataLine(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.isBlank() && !line.startsWith("#")) {
                return i;
            }
        }
        return -1;
    }

    private Map<String, Integer> header(String line) {
        String[] columns = line.split("\t", -1);
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < columns.length; i++) {
            result.put(columns[i].trim().toLowerCase(), i);
        }
        return result;
    }

    private String required(Map<String, Integer> header, String[] values, String column) {
        String value = optional(header, values, column);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required clinical decision column: " + column);
        }
        return value;
    }

    private String optional(Map<String, Integer> header, String[] values, String column) {
        Integer index = header.get(column);
        if (index == null || index >= values.length) {
            return null;
        }
        String value = values[index].trim();
        return value.isEmpty() ? null : value;
    }

    private Double optionalDouble(Map<String, Integer> header, String[] values, String column) {
        String value = optional(header, values, column);
        return value == null ? null : Double.parseDouble(value);
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private SegmentContext segmentContext(long segmentId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT gs.sample_test_result_id, sa.individual_id
                FROM genomic_segments gs
                JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
                WHERE gs.segment_id = ?
                """)) {
            ps.setLong(1, segmentId);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("No genomic segment found for segment_id " + segmentId);
                }
                return new SegmentContext(
                        rs.getLong("sample_test_result_id"),
                        rs.getLong("individual_id"));
            }
        }
    }

    private record SegmentContext(long sampleTestResultId, long individualId) {
    }
}

package org.mpgdatabase.dao;

import org.mpgdatabase.model.Models.Note;
import org.mpgdatabase.model.Models.SignedOutCall;
import org.mpgdatabase.model.Models.VariantClassification;
import org.mpgdatabase.model.Models.InterpretedCall;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

public class ClinicalDecisionDao {
    private final Connection connection;

    public ClinicalDecisionDao(Connection connection) {
        this.connection = connection;
    }

    public long createVariantClassification(VariantClassification classification) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO variant_classifications
                    (interpreted_call_id, classification_label, classification_source, guideline_system, guideline_version,
                     evidence_score, evidence_summary, classified_by, review_status,
                     is_current, supersedes_classification_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, DaoSupport.returnGeneratedKeys())) {
            ps.setLong(1, classification.interpretedCallId());
            ps.setString(2, classification.classificationLabel());
            ps.setString(3, classification.classificationSource());
            ps.setString(4, classification.guidelineSystem());
            ps.setString(5, classification.guidelineVersion());
            if (classification.evidenceScore() == null) {
                ps.setNull(6, Types.DOUBLE);
            } else {
                ps.setDouble(6, classification.evidenceScore());
            }
            ps.setString(7, classification.evidenceSummary());
            ps.setString(8, classification.classifiedBy());
            ps.setString(9, classification.reviewStatus());
            ps.setBoolean(10, classification.current());
            if (classification.supersedesClassificationId() == null) {
                ps.setNull(11, Types.BIGINT);
            } else {
                ps.setLong(11, classification.supersedesClassificationId());
            }
            ps.executeUpdate();
            return DaoSupport.generatedId(ps);
        }
    }

    public long createSignedOutCall(SignedOutCall signedOutCall) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO signed_out_calls
                    (interpreted_call_id, classification_id, individual_id, sample_test_result_id,
                     clinical_significance, reportability_status, relevance_to_indication, interpretation_text,
                     signed_out_status, signed_out_by, signed_out_at, report_text,
                     report_version, amended_from_signed_out_call_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?)
                """, DaoSupport.returnGeneratedKeys())) {
            ps.setLong(1, signedOutCall.interpretedCallId());
            ps.setLong(2, signedOutCall.classificationId());
            ps.setLong(3, signedOutCall.individualId());
            ps.setLong(4, signedOutCall.sampleTestResultId());
            ps.setString(5, signedOutCall.clinicalSignificance());
            ps.setString(6, signedOutCall.reportabilityStatus());
            ps.setString(7, signedOutCall.relevanceToIndication());
            ps.setString(8, signedOutCall.interpretationText());
            ps.setString(9, signedOutCall.signedOutStatus());
            ps.setString(10, signedOutCall.signedOutBy());
            ps.setString(11, signedOutCall.reportText());
            ps.setString(12, signedOutCall.reportVersion());
            if (signedOutCall.amendedFromSignedOutCallId() == null) {
                ps.setNull(13, Types.BIGINT);
            } else {
                ps.setLong(13, signedOutCall.amendedFromSignedOutCallId());
            }
            ps.executeUpdate();
            return DaoSupport.generatedId(ps);
        }
    }

    public long createNote(Note note) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO notes (target_table, target_id, note_type, note_text, created_by)
                VALUES (?, ?, ?, ?, ?)
                """, DaoSupport.returnGeneratedKeys())) {
            ps.setString(1, note.targetTable());
            ps.setLong(2, note.targetId());
            ps.setString(3, note.noteType());
            ps.setString(4, note.noteText());
            ps.setString(5, note.createdBy());
            ps.executeUpdate();
            return DaoSupport.generatedId(ps);
        }
    }

    public long createInterpretedCall(InterpretedCall interpretedCall) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO interpreted_calls (finding_type, finding_id, sample_test_result_id, individual_id)
                VALUES (?, ?, ?, ?)
                """, DaoSupport.returnGeneratedKeys())) {
            ps.setString(1, interpretedCall.findingType());
            ps.setLong(2, interpretedCall.findingId());
            ps.setLong(3, interpretedCall.sampleTestResultId());
            ps.setLong(4, interpretedCall.individualId());
            ps.executeUpdate();
            return DaoSupport.generatedId(ps);
        }
    }

    public List<String> clinicalTraceBySegment(long segmentId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT
                    vc.classification_label,
                    vc.evidence_summary,
                    soc.clinical_significance,
                    soc.signed_out_status,
                    n.note_text
                FROM variant_classifications vc
                LEFT JOIN signed_out_calls soc ON soc.classification_id = vc.classification_id
                LEFT JOIN notes n
                    ON n.target_table = 'signed_out_calls'
                   AND n.target_id = soc.signed_out_call_id
                JOIN interpreted_calls ic ON ic.interpreted_call_id = vc.interpreted_call_id
                WHERE ic.finding_type = 'genomic_segments'
                  AND ic.finding_id = ?
                ORDER BY vc.classification_id, soc.signed_out_call_id, n.note_id
                """)) {
            ps.setLong(1, segmentId);
            List<String> rows = new ArrayList<>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(String.join("\t",
                            nullToEmpty(rs.getString("classification_label")),
                            nullToEmpty(rs.getString("evidence_summary")),
                            nullToEmpty(rs.getString("clinical_significance")),
                            nullToEmpty(rs.getString("signed_out_status")),
                            nullToEmpty(rs.getString("note_text"))));
                }
            }
            return rows;
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

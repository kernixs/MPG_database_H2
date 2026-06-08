package org.mpgdatabase.dao;

import org.mpgdatabase.model.Models.Note;
import org.mpgdatabase.model.Models.SignedOutCall;
import org.mpgdatabase.model.Models.VariantClassification;

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
                    (segment_id, classification_label, guideline_system, guideline_version,
                     evidence_score, evidence_summary, classified_by, review_status,
                     is_current, supersedes_classification_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, DaoSupport.returnGeneratedKeys())) {
            ps.setLong(1, classification.segmentId());
            ps.setString(2, classification.classificationLabel());
            ps.setString(3, classification.guidelineSystem());
            ps.setString(4, classification.guidelineVersion());
            if (classification.evidenceScore() == null) {
                ps.setNull(5, Types.DOUBLE);
            } else {
                ps.setDouble(5, classification.evidenceScore());
            }
            ps.setString(6, classification.evidenceSummary());
            ps.setString(7, classification.classifiedBy());
            ps.setString(8, classification.reviewStatus());
            ps.setBoolean(9, classification.current());
            if (classification.supersedesClassificationId() == null) {
                ps.setNull(10, Types.BIGINT);
            } else {
                ps.setLong(10, classification.supersedesClassificationId());
            }
            ps.executeUpdate();
            return DaoSupport.generatedId(ps);
        }
    }

    public long createSignedOutCall(SignedOutCall signedOutCall) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO signed_out_calls
                    (segment_id, classification_id, individual_id, sample_test_result_id,
                     clinical_significance, relevance_to_indication, interpretation_text,
                     signed_out_status, signed_out_by, signed_out_at, report_text,
                     report_version, amended_from_signed_out_call_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?)
                """, DaoSupport.returnGeneratedKeys())) {
            ps.setLong(1, signedOutCall.segmentId());
            ps.setLong(2, signedOutCall.classificationId());
            ps.setLong(3, signedOutCall.individualId());
            ps.setLong(4, signedOutCall.sampleTestResultId());
            ps.setString(5, signedOutCall.clinicalSignificance());
            ps.setString(6, signedOutCall.relevanceToIndication());
            ps.setString(7, signedOutCall.interpretationText());
            ps.setString(8, signedOutCall.signedOutStatus());
            ps.setString(9, signedOutCall.signedOutBy());
            ps.setString(10, signedOutCall.reportText());
            ps.setString(11, signedOutCall.reportVersion());
            if (signedOutCall.amendedFromSignedOutCallId() == null) {
                ps.setNull(12, Types.BIGINT);
            } else {
                ps.setLong(12, signedOutCall.amendedFromSignedOutCallId());
            }
            ps.executeUpdate();
            return DaoSupport.generatedId(ps);
        }
    }

    public long createNote(Note note) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO notes (target_table, target_id, note_type, note_text, author)
                VALUES (?, ?, ?, ?, ?)
                """, DaoSupport.returnGeneratedKeys())) {
            ps.setString(1, note.targetTable());
            ps.setLong(2, note.targetId());
            ps.setString(3, note.noteType());
            ps.setString(4, note.noteText());
            ps.setString(5, note.author());
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
                WHERE vc.segment_id = ?
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

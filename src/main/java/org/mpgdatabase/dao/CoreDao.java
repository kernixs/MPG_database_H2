package org.mpgdatabase.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class CoreDao {
    private final Connection connection;

    public CoreDao(Connection connection) {
        this.connection = connection;
    }

    public long findOrCreateIndividual(String externalIdentifier) throws SQLException {
        String value = externalIdentifier == null || externalIdentifier.isBlank()
                ? "IND-" + System.nanoTime()
                : externalIdentifier;
        Long existing = findId("SELECT individual_id FROM individuals WHERE external_identifier = ?", value);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO individuals (external_identifier) VALUES (?)",
                DaoSupport.returnGeneratedKeys())) {
            ps.setString(1, value);
            ps.executeUpdate();
            return DaoSupport.generatedId(ps);
        }
    }

    public long findOrCreateSampleAccession(String accessionIdentifier, long individualId, String dnaSource)
            throws SQLException {
        Long existing = findId(
                "SELECT sample_accession_id FROM sample_accessions WHERE accession_identifier = ?",
                accessionIdentifier);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO sample_accessions (accession_identifier, individual_id, dna_source)
                VALUES (?, ?, ?)
                """, DaoSupport.returnGeneratedKeys())) {
            ps.setString(1, accessionIdentifier);
            ps.setLong(2, individualId);
            ps.setString(3, dnaSource);
            ps.executeUpdate();
            return DaoSupport.generatedId(ps);
        }
    }

    public long findOrCreateLabProtocol(String technology, String manufacturer, String miscellaneous)
            throws SQLException {
        Long existing = findId("""
                SELECT lab_protocol_id FROM lab_protocols
                WHERE technology = ? AND COALESCE(manufacturer, '') = COALESCE(?, '')
                """, technology, manufacturer);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO lab_protocols (technology, manufacturer, miscellaneous)
                VALUES (?, ?, ?)
                """, DaoSupport.returnGeneratedKeys())) {
            ps.setString(1, technology);
            ps.setString(2, manufacturer);
            ps.setString(3, miscellaneous);
            ps.executeUpdate();
            return DaoSupport.generatedId(ps);
        }
    }

    public long createSampleTest(long sampleAccessionId, long labProtocolId, String testType) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO sample_tests (sample_accession_id, lab_protocol_id, test_type)
                VALUES (?, ?, ?)
                """, DaoSupport.returnGeneratedKeys())) {
            ps.setLong(1, sampleAccessionId);
            ps.setLong(2, labProtocolId);
            ps.setString(3, testType);
            ps.executeUpdate();
            return DaoSupport.generatedId(ps);
        }
    }

    public long findOrCreatePipeline(String softwareName, String softwareVersion, String settingsUsed)
            throws SQLException {
        Long existing = findId("""
                SELECT pipeline_id FROM pipelines
                WHERE software_name = ? AND COALESCE(software_version, '') = COALESCE(?, '')
                """, softwareName, softwareVersion);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO pipelines (software_name, software_version, settings_used)
                VALUES (?, ?, ?)
                """, DaoSupport.returnGeneratedKeys())) {
            ps.setString(1, softwareName);
            ps.setString(2, softwareVersion);
            ps.setString(3, settingsUsed);
            ps.executeUpdate();
            return DaoSupport.generatedId(ps);
        }
    }

    public long createSampleTestResult(
            long sampleTestId,
            long pipelineId,
            Long sourceFileId,
            String genomeBuild,
            String callingMethod,
            String rawIscn,
            String annotationNames,
            Integer lineNumber
    ) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO sample_test_results
                    (sample_test_id, pipeline_id, source_file_id, genome_build, calling_method, raw_iscn, annotation_names, line_number)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, DaoSupport.returnGeneratedKeys())) {
            ps.setLong(1, sampleTestId);
            ps.setLong(2, pipelineId);
            if (sourceFileId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, sourceFileId);
            }
            ps.setString(4, genomeBuild);
            ps.setString(5, callingMethod);
            ps.setString(6, rawIscn);
            ps.setString(7, annotationNames);
            if (lineNumber == null) {
                ps.setNull(8, java.sql.Types.INTEGER);
            } else {
                ps.setInt(8, lineNumber);
            }
            ps.executeUpdate();
            return DaoSupport.generatedId(ps);
        }
    }

    public long createSourceFile(
            String fileName,
            String filePath,
            long pipelineId,
            String importStatus,
            int rowCount,
            String notes
    ) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO source_files (file_name, file_path, pipeline_id, import_status, row_count, notes)
                VALUES (?, ?, ?, ?, ?, ?)
                """, DaoSupport.returnGeneratedKeys())) {
            ps.setString(1, fileName);
            ps.setString(2, filePath);
            ps.setLong(3, pipelineId);
            ps.setString(4, importStatus);
            ps.setInt(5, rowCount);
            ps.setString(6, notes);
            ps.executeUpdate();
            return DaoSupport.generatedId(ps);
        }
    }

    public void updateSourceFileStatus(long sourceFileId, String importStatus, int rowCount) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE source_files
                SET import_status = ?, row_count = ?
                WHERE source_file_id = ?
                """)) {
            ps.setString(1, importStatus);
            ps.setInt(2, rowCount);
            ps.setLong(3, sourceFileId);
            ps.executeUpdate();
        }
    }

    public long createKaryotype(long sampleTestResultId, String karyotypeText) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO karyotypes (sample_test_result_id, karyotype_text, abnormalities)
                VALUES (?, ?, ?)
                """, DaoSupport.returnGeneratedKeys())) {
            ps.setLong(1, sampleTestResultId);
            ps.setString(2, karyotypeText);
            ps.setString(3, karyotypeText);
            ps.executeUpdate();
            return DaoSupport.generatedId(ps);
        }
    }

    public long createGenomicEvent(
            long sampleTestResultId,
            Long sourceFileId,
            String eventGroupId,
            String eventType,
            String genomeBuild,
            String callingMethod,
            String rawEventText,
            Integer lineNumber,
            String eventStatus,
            String confidence,
            String annotations
    ) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO genomic_events
                    (sample_test_result_id, source_file_id, event_group_id, event_type, genome_build, calling_method,
                     raw_event_text, line_number, event_status, confidence, annotations)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, DaoSupport.returnGeneratedKeys())) {
            ps.setLong(1, sampleTestResultId);
            if (sourceFileId == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, sourceFileId);
            }
            ps.setString(3, eventGroupId);
            ps.setString(4, eventType);
            ps.setString(5, genomeBuild);
            ps.setString(6, callingMethod);
            ps.setString(7, rawEventText);
            if (lineNumber == null) {
                ps.setNull(8, java.sql.Types.INTEGER);
            } else {
                ps.setInt(8, lineNumber);
            }
            ps.setString(9, eventStatus);
            ps.setString(10, confidence);
            ps.setString(11, annotations);
            ps.executeUpdate();
            return DaoSupport.generatedId(ps);
        }
    }

    public long createGenomicLink(
            long eventId,
            long sourceSegmentId,
            long targetSegmentId,
            String linkType,
            String orientation,
            String evidence,
            String confidence
    ) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO genomic_links
                    (event_id, source_segment_id, target_segment_id, link_type, orientation, evidence, confidence)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, DaoSupport.returnGeneratedKeys())) {
            ps.setLong(1, eventId);
            ps.setLong(2, sourceSegmentId);
            ps.setLong(3, targetSegmentId);
            ps.setString(4, linkType);
            ps.setString(5, orientation);
            ps.setString(6, evidence);
            ps.setString(7, confidence);
            ps.executeUpdate();
            return DaoSupport.generatedId(ps);
        }
    }

    public long createValidationIssue(Long segmentId, String issueType, String issueMessage, String severity)
            throws SQLException {
        return createValidationIssue(segmentId, null, null, null, issueType, issueMessage, severity);
    }

    public long createValidationIssue(
            Long segmentId,
            Long sourceFileId,
            Integer lineNumber,
            String sampleAccessionId,
            String issueType,
            String issueMessage,
            String severity
    )
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO validation_issues
                    (segment_id, source_file_id, line_number, sample_accession_id, issue_type, issue_message, severity)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, DaoSupport.returnGeneratedKeys())) {
            if (segmentId == null) {
                ps.setNull(1, java.sql.Types.BIGINT);
            } else {
                ps.setLong(1, segmentId);
            }
            if (sourceFileId == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, sourceFileId);
            }
            if (lineNumber == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setInt(3, lineNumber);
            }
            ps.setString(4, sampleAccessionId);
            ps.setString(5, issueType);
            ps.setString(6, issueMessage);
            ps.setString(7, severity);
            ps.executeUpdate();
            return DaoSupport.generatedId(ps);
        }
    }

    private Long findId(String sql, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, value);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private Long findId(String sql, String first, String second) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, first);
            ps.setString(2, second);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }
}

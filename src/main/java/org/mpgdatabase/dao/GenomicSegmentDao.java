package org.mpgdatabase.dao;

import org.mpgdatabase.model.Models.GenomicSegment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

public class GenomicSegmentDao {
    private final Connection connection;

    public GenomicSegmentDao(Connection connection) {
        this.connection = connection;
    }

    public long create(GenomicSegment segment) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO genomic_segments
                    (event_group_id, sample_test_result_id, karyotype_id, chromosome, start_pos, stop_pos,
                     event_type, copy_number, genome_build, confidence, array_score, number_of_sites, raw_iscn,
                     raw_segment_text, annotations)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, DaoSupport.returnGeneratedKeys())) {
            ps.setString(1, segment.eventGroupId());
            ps.setLong(2, segment.sampleTestResultId());
            if (segment.karyotypeId() == null) {
                ps.setNull(3, Types.BIGINT);
            } else {
                ps.setLong(3, segment.karyotypeId());
            }
            ps.setString(4, segment.chromosome());
            ps.setLong(5, segment.startPos());
            ps.setLong(6, segment.stopPos());
            ps.setString(7, segment.eventType());
            ps.setInt(8, segment.copyNumber());
            ps.setString(9, segment.genomeBuild());
            ps.setString(10, segment.confidence());
            if (segment.arrayScore() == null) {
                ps.setNull(11, Types.DOUBLE);
            } else {
                ps.setDouble(11, segment.arrayScore());
            }
            if (segment.numberOfSites() == null) {
                ps.setNull(12, Types.INTEGER);
            } else {
                ps.setInt(12, segment.numberOfSites());
            }
            ps.setString(13, segment.rawIscn());
            ps.setString(14, segment.rawSegmentText());
            ps.setString(15, segment.annotations());
            ps.executeUpdate();
            return DaoSupport.generatedId(ps);
        }
    }

    public List<GenomicSegment> findAll() throws SQLException {
        return query("""
                SELECT * FROM genomic_segments
                ORDER BY segment_id
                """);
    }

    public List<GenomicSegment> findBySampleAccession(String accessionIdentifier) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT gs.* FROM genomic_segments gs
                JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
                JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
                JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
                WHERE sa.accession_identifier = ?
                ORDER BY gs.segment_id
                """)) {
            ps.setString(1, accessionIdentifier);
            return readSegments(ps);
        }
    }

    public List<GenomicSegment> findOverlapping(String chromosome, long start, long stop) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM genomic_segments
                WHERE chromosome = ? AND start_pos <= ? AND stop_pos >= ?
                ORDER BY segment_id
                """)) {
            ps.setString(1, chromosome);
            ps.setLong(2, stop);
            ps.setLong(3, start);
            return readSegments(ps);
        }
    }

    public List<GenomicSegment> findIscnDerived() throws SQLException {
        return query("""
                SELECT gs.* FROM genomic_segments gs
                JOIN karyotypes k ON k.karyotype_id = gs.karyotype_id
                ORDER BY gs.segment_id
                """);
    }

    private List<GenomicSegment> query(String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            return readSegments(ps);
        }
    }

    private List<GenomicSegment> readSegments(PreparedStatement ps) throws SQLException {
        List<GenomicSegment> rows = new ArrayList<>();
        try (var rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new GenomicSegment(
                        rs.getLong("segment_id"),
                        rs.getString("event_group_id"),
                        rs.getLong("sample_test_result_id"),
                        DaoSupport.nullableLong(rs, "karyotype_id"),
                        rs.getString("chromosome"),
                        rs.getLong("start_pos"),
                        rs.getLong("stop_pos"),
                        rs.getString("event_type"),
                        rs.getInt("copy_number"),
                        rs.getString("genome_build"),
                        rs.getString("confidence"),
                        DaoSupport.nullableDouble(rs, "array_score"),
                        DaoSupport.nullableInt(rs, "number_of_sites"),
                        rs.getString("raw_iscn"),
                        rs.getString("raw_segment_text"),
                        rs.getString("annotations")
                ));
            }
        }
        return rows;
    }
}

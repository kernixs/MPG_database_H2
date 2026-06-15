package org.mpgdatabase.dao;

import org.mpgdatabase.model.Models.SmallVariant;
import org.mpgdatabase.model.Models.SmallVariantAnnotation;
import org.mpgdatabase.model.Models.SmallVariantSampleCall;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

public class SmallVariantDao {
    private final Connection connection;

    public SmallVariantDao(Connection connection) {
        this.connection = connection;
    }

    public long findOrCreateVariant(SmallVariant variant) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT small_variant_id
                FROM small_variants
                WHERE normalized_key = ?
                """)) {
            ps.setString(1, variant.normalizedKey());
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO small_variants
                    (chromosome, position, variant_id, ref_allele, alt_allele,
                     variant_type, genome_build, normalized_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, DaoSupport.returnGeneratedKeys())) {
            ps.setString(1, variant.chromosome());
            ps.setLong(2, variant.position());
            ps.setString(3, variant.variantId());
            ps.setString(4, variant.refAllele());
            ps.setString(5, variant.altAllele());
            ps.setString(6, variant.variantType());
            ps.setString(7, variant.genomeBuild());
            ps.setString(8, variant.normalizedKey());
            ps.executeUpdate();
            return DaoSupport.generatedId(ps);
        }
    }

    public long createSampleCall(SmallVariantSampleCall call) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO small_variant_sample_calls
                    (small_variant_id, sample_test_result_id, qual, filter_status, genotype,
                     phased, ref_depth, alt_depth, total_depth, genotype_quality, allele_balance,
                     format_keys, sample_values, info_raw, raw_vcf_line, line_number)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, DaoSupport.returnGeneratedKeys())) {
            ps.setLong(1, call.smallVariantId());
            ps.setLong(2, call.sampleTestResultId());
            setDouble(ps, 3, call.qual());
            ps.setString(4, call.filterStatus());
            ps.setString(5, call.genotype());
            setBoolean(ps, 6, call.phased());
            setInt(ps, 7, call.refDepth());
            setInt(ps, 8, call.altDepth());
            setInt(ps, 9, call.totalDepth());
            setDouble(ps, 10, call.genotypeQuality());
            setDouble(ps, 11, call.alleleBalance());
            ps.setString(12, call.formatKeys());
            ps.setString(13, call.sampleValues());
            ps.setString(14, call.infoRaw());
            ps.setString(15, call.rawVcfLine());
            ps.setInt(16, call.lineNumber());
            ps.executeUpdate();
            return DaoSupport.generatedId(ps);
        }
    }

    public void createAnnotation(SmallVariantAnnotation annotation) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO small_variant_annotations
                    (small_variant_id, gene, gene_id, transcript, consequence, impact,
                     hgvs_c, hgvs_p, clinvar_status, population_af, annotation_source,
                     annotation_version, annotation_raw, is_primary_transcript)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setLong(1, annotation.smallVariantId());
            ps.setString(2, annotation.gene());
            ps.setString(3, annotation.geneId());
            ps.setString(4, annotation.transcript());
            ps.setString(5, annotation.consequence());
            ps.setString(6, annotation.impact());
            ps.setString(7, annotation.hgvsC());
            ps.setString(8, annotation.hgvsP());
            ps.setString(9, annotation.clinvarStatus());
            setDouble(ps, 10, annotation.populationAf());
            ps.setString(11, annotation.annotationSource());
            ps.setString(12, annotation.annotationVersion());
            ps.setString(13, annotation.annotationRaw());
            setBoolean(ps, 14, annotation.isPrimaryTranscript());
            ps.executeUpdate();
        }
    }

    private void setInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private void setDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    private void setBoolean(PreparedStatement ps, int index, Boolean value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BOOLEAN);
        } else {
            ps.setBoolean(index, value);
        }
    }
}

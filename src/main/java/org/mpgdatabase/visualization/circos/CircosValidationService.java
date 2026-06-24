package org.mpgdatabase.visualization.circos;

import java.util.Locale;
import java.util.Set;

public class CircosValidationService {
    private static final Set<String> HUMAN_CHROMOSOMES = Set.of(
            "chr1", "chr2", "chr3", "chr4", "chr5", "chr6", "chr7", "chr8",
            "chr9", "chr10", "chr11", "chr12", "chr13", "chr14", "chr15", "chr16",
            "chr17", "chr18", "chr19", "chr20", "chr21", "chr22", "chrX", "chrY"
    );

    public String circlizeSpecies(String genomeBuild) {
        if (genomeBuild == null || genomeBuild.isBlank()) {
            throw new IllegalArgumentException("Cannot generate Circos plot because genome build is missing.");
        }
        String normalized = genomeBuild.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("grch37") || normalized.equals("hg19") || normalized.contains("37")) {
            return "hg19";
        }
        if (normalized.equals("grch38") || normalized.equals("hg38") || normalized.contains("38")) {
            return "hg38";
        }
        throw new IllegalArgumentException("Unsupported genome build for Circos plot: " + genomeBuild);
    }

    public String normalizeChromosome(String chromosome) {
        if (chromosome == null || chromosome.isBlank()) {
            throw new IllegalArgumentException("Chromosome value is missing.");
        }
        String cleaned = chromosome.trim();
        String withoutPrefix = cleaned.toLowerCase(Locale.ROOT).startsWith("chr")
                ? cleaned.substring(3)
                : cleaned;
        String normalized = "chr" + withoutPrefix.toUpperCase(Locale.ROOT);
        if (withoutPrefix.matches("\\d+")) {
            normalized = "chr" + Integer.parseInt(withoutPrefix);
        }
        if (!HUMAN_CHROMOSOMES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported chromosome for built-in human ideogram: " + chromosome);
        }
        return normalized;
    }

    public void validateInterval(String chr, long start, long stop, String recordLabel) {
        normalizeChromosome(chr);
        if (start < 0 || stop < 0) {
            throw new IllegalArgumentException(recordLabel + " has negative coordinates.");
        }
        if (start > stop) {
            throw new IllegalArgumentException(recordLabel + " has start greater than stop.");
        }
    }
}

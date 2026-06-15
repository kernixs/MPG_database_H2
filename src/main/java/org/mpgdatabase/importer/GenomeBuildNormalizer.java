package org.mpgdatabase.importer;

import java.util.Locale;

public final class GenomeBuildNormalizer {
    private GenomeBuildNormalizer() {
    }

    public static String normalize(String build) {
        if (build == null || build.isBlank()) {
            return null;
        }
        String normalized = build.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[_\\-]+", " ")
                .replaceAll("\\s+", " ");
        if (normalized.contains("homo sapiens assembly38")
                || normalized.contains("assembly38")
                || normalized.contains("grch38")
                || normalized.contains("hg38")) {
            return "GRCh38";
        }
        if (normalized.contains("homo sapiens assembly37")
                || normalized.contains("assembly37")
                || normalized.contains("grch37")
                || normalized.contains("hg19")
                || normalized.contains("build37")) {
            return "GRCh37";
        }

        return switch (normalized) {
            case "grch37", "hg19", "build 37", "b37", "genome build 37", "human genome build 37" -> "GRCh37";
            case "grch38", "hg38", "build 38", "b38", "genome build 38", "human genome build 38" -> "GRCh38";
            case "t2t", "chm13", "t2t chm13", "chm13v2", "chm13v2.0" -> "T2T-CHM13";
            case "ncbi36", "hg18", "build 36", "b36" -> "NCBI36";
            default -> null;
        };
    }
}

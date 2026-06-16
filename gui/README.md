# Genomic Database GUI Prototype

This folder contains a Java Swing GUI for the H2 genomic database prototype. The GUI uses the same shared schema and importer services as the CLI for CNV/SV and SNV/indel VCF imports.

## Build

From the main project folder:

```bash
./apache-maven-3.9.15/bin/mvn -f gui/pom.xml package
```

The build creates a runnable JAR with H2 included:

```text
gui/target/cnv-database-gui-0.1.0.jar
```

A prebuilt copy is also kept in:

```text
gui/dist/cnv-database-gui-0.1.0.jar
```

## Run

```bash
java -jar gui/target/cnv-database-gui-0.1.0.jar
```

or:

```bash
java -jar gui/dist/cnv-database-gui-0.1.0.jar
```

The GUI stores its local config next to the JAR:

```text
gui/target/db_gui.properties
```

An example config file is provided at:

```text
gui/db_gui.properties.example
```

By default, the app tries to use the main prototype database:

```text
../output/mpg_database_h2
```

You can also use **File -> Open/Create Database...** to select a different H2 file database.

## What The GUI Does

- Connects to an H2 database.
- Creates the schema automatically if the database is empty.
- Shows database path, connection status, and summary counts.
- Imports one file at a time.
- Routes `.vcf` files to the SNV/indel VCF importer.
- Routes `.cnv`, `.tsv`, and `.txt` files to the CNV/SV importer.
- Stores CNV/SV rows in `genomic_segments`, `segment_annotations`, and `genomic_links`.
- Stores SNV/indel rows in `small_variants`, `small_variant_sample_calls`, and `small_variant_annotations`.
- Runs read-only SQL queries only.
- Rejects write SQL such as `INSERT`, `UPDATE`, `DELETE`, `DROP`, or `ALTER`.
- Provides query presets from the **Query Presets** menu.
- Shows query results in a `JTable`.
- Exports the currently displayed table to TSV without rerunning the query.

## GUI Import Rule

The GUI uses the shared importers. It imports valid records and records rejected/unsupported rows in `validation_issues`.

```text
SNV/indel: .vcf only
CNV/SV: .cnv, .tsv, .txt with supported CNV/SV columns
```

VCF structural-variant records such as symbolic `<DEL>` or breakend/BND records are recognized but skipped in the V1 SNV/indel importer. They are recorded as warnings:

```text
Unsupported VCF Structural Variant
```

The current CNV/SV required fields are:

```text
sample_accession_id
chromosome
start_pos
stop_pos
event_type
copy_number
genome_build
```

Genome build aliases are normalized before storage:

```text
hg19, Build 37, b37 -> GRCh37
hg38, Build 38, b38 -> GRCh38
T2T, CHM13, CHM13v2 -> T2T-CHM13
```

Unsupported build names are rejected instead of being stored as free text.

Optional supported fields include:

```text
copy_number
event_group_id
confidence
array_score
number_of_sites
raw_iscn
annotation_names
annotations
format-specific extra columns
```

If `copy_number` is missing, the GUI can infer it from the event type:

```text
DEL / LOSS -> 1
DUP / GAIN / AMP -> 3
NEUTRAL / INV / INS / TRANS / T / DER -> 2
```

This allows simple NGS/WGS-derived files with `SV_Type` but no explicit `copy_number` to import.

For generic translocation-style rows, use:

```text
event_group_id
event_type = TRANS or T
```

When multiple rows share the same result context and `event_group_id`, the GUI preserves that label directly on each `genomic_segments` row. If two or more grouped breakpoint rows are imported, the GUI also creates `genomic_links` rows with the same `event_group_id` and mapped link types such as `TRANSLOCATION`, `INVERSION`, `DERIVATIVE`, and `RING`. Common aliases such as `group_id`, `variant_id`, `pair_id`, `link_id`, and `breakend_id` are accepted.

New GUI imports do not require `genomic_events` or new `genomic_event_groups` rows. The repeated `event_group_id` is stored directly on `genomic_segments.event_group_id` and on any generated `genomic_links.event_group_id`.

Rows without `event_group_id` are imported as standalone CNV segments. They do not get a link unless another rule creates one.

To verify linked events in the GUI, inspect `genomic_links` directly with the translocation query shown below.

The legacy event group table remains available for older database files, but it is not used by new GUI imports or query presets.

## Sample Data

Test inputs are provided under:

```text
gui/sample-data/
```

Files:

```text
valid_cnv.tsv
invalid_missing_genome_build.tsv
interesting_chr5_overlap.tsv
mixed_event_types.tsv
ngs_derived_small.tsv
translocation_pair.tsv
```

## Suggested Manual Test

1. Build and run the JAR.
2. Use **File -> Open/Create Database...** and choose a database file, or accept the remembered/default database.
3. Import a CNV/SV file:

```text
gui/sample-data/valid_cnv.tsv
```

4. Import a VCF file:

```text
../data/small_example.filtered_snpEff.ann.vcf
```

5. Try preset queries:

```text
Universal genomic results
Show all CNV/SV segments
Show all SNV/indel variants
Count CNV calls by source file
CNV calls overlapping interval
SNV/indel by gene
```

5. Try a custom read-only SQL query:

```sql
SELECT
    sa.accession_identifier AS sample_id,
    gs.chromosome,
    gs.start_pos,
    gs.stop_pos,
    gs.event_type,
    gs.copy_number,
    str.genome_build
FROM genomic_segments gs
JOIN sample_test_results str ON str.sample_test_result_id = gs.sample_test_result_id
JOIN sample_tests st ON st.sample_test_id = str.sample_test_id
JOIN sample_accessions sa ON sa.sample_accession_id = st.sample_accession_id
WHERE gs.chromosome = 'chr5'
LIMIT 100
```

To inspect translocation links after importing `gui/sample-data/translocation_pair.tsv`:

```text
                 genomic_segments
              segment_id   segment_id
                  ^             ^
                  |             |
source_segment_id |             | target_segment_id
                  |             |
              genomic_links
```

There is one `genomic_segments` table. In the query below, it is joined twice with aliases `src` and `tgt` so each link can expose both endpoints for visualization.

```sql
SELECT
    gl.link_id,
    gl.event_group_id,
    src.chromosome AS source_chr,
    src.start_pos AS source_start,
    src.stop_pos AS source_stop,
    tgt.chromosome AS target_chr,
    tgt.start_pos AS target_start,
    tgt.stop_pos AS target_stop,
    gl.link_type,
    gl.evidence
FROM genomic_links gl
JOIN genomic_segments src ON src.segment_id = gl.source_segment_id
JOIN genomic_segments tgt ON tgt.segment_id = gl.target_segment_id
ORDER BY gl.link_id DESC
LIMIT 100
```

## Scope

This is a first GUI prototype. It is not trying to be pixel-perfect, and it does not include authentication, row editing, installer packaging, or clinical workflow screens.

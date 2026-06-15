# MPG Database H2 Phase 2.6 Prototype

This repository contains the Phase 2.6 prototype for a lightweight genomic database backbone. It is a CLI-only Java application that initializes an H2 database, imports simple CNV files, stores normalized genomic events, stores early clinical decision records, and verifies that the expected schema and data relationships work.

The larger product vision is a clinician-centered interpretation and visualization platform for cytogenetic, molecular, and genomic findings. This Phase 2.6 project is intentionally narrower: it builds the relational data layer that later UI, API, visualization, and richer clinical interpretation tools can use.

## Why This Prototype Exists

Clinical genomic interpretation needs traceability. A genomic segment should not appear as an isolated row with no context. It should be possible to answer:

- Which individual/sample did this result come from?
- Which laboratory method generated the data?
- Which software or parser processed it?
- Was the event derived from ISCN or array-style CNV data?
- What was the original ISCN string, if one existed?
- Can the normalized genomic interval be queried by sample or genomic region?
- Were malformed records or missing metadata captured instead of silently ignored?
- Was a finding classified, clinically interpreted, reviewed, or signed out?

This prototype focuses on those questions. It stores the clinical decision layer, but it does not yet try to solve the full clinical UI, phenotype, reporting, or integration workflow.

## Current Scope

Implemented:

- H2 relational schema creation through JDBC.
- Java entity-style records and DAO classes.
- TSV-style CNV import.
- Pluggable importer design so future CNV formats can be added.
- Automatic calling-method detection from metadata, columns, and file names.
- Manual calling-method override for import runs.
- Preservation of raw ISCN strings.
- Creation of karyotype records for ISCN-derived segments.
- Array-derived segments without requiring karyotypes.
- NGS/WGS DEL/DUP import from classified structural-variant result tables.
- Generic annotation storage through `annotation_names` and `annotations`.
- Flexible CLI search filters.
- Validation issue capture for malformed records and unresolved genome builds.
- Variant classification storage with evidence summaries.
- Patient/case-specific clinical interpretation storage.
- Reviewer note storage.
- Sign-out status and report-text storage.
- Required verification checks after import.
- Generated report files under `output/`.
- In-memory H2 tests and file-backed H2 manual runs.

Intentionally not implemented in Phase 2.6:

- UI.
- REST API.
- HPO integration.
- ACMG scoring.
- Phenotype tracking.
- Report templates or amended-report workflow automation.
- Genome browser visualization.
- EMR/Epic/FileMaker integration.

Those belong to later phases or parallel work.

## Technology Choices

### Java

Java is the chosen implementation direction for the larger platform. This prototype uses plain Java so that the database, import, and verification logic are easy to inspect and migrate.

The Maven compiler target is Java 21:

```xml
<maven.compiler.release>21</maven.compiler.release>
```

The local machine currently has Java 25 installed, which can compile and run Java 21-targeted code.

### H2

H2 is used because it is lightweight and easy to run locally. It supports both:

- in-memory databases for tests
- file-backed databases for manual inspection

The schema is written in SQL in:

```text
src/main/resources/schema.sql
```

This keeps the schema visible and portable. A future PostgreSQL migration should start from this SQL model rather than from hidden ORM behavior.

### JDBC

The prototype uses JDBC directly instead of a framework or ORM. This was chosen because Phase 2 is about understanding and proving the relational shape. Prepared statements are used for database writes and queries.

### Maven

Maven manages dependencies and test execution. A local copy of Maven was installed inside this workspace:

```text
apache-maven-3.9.15/bin/mvn
```

This avoids requiring a system-wide Maven installation.

## Database Design

The design separates real-world clinical identifiers from database primary keys.

Relational joins use surrogate auto-increment keys:

```text
individual_id
sample_accession_id
sample_test_id
sample_test_result_id
segment_id
```

Real-world identifiers are stored as separate fields, for example:

```text
sample_accessions.accession_identifier
individuals.mrn
individuals.external_identifier
```

This keeps joins stable and efficient while preserving meaningful identifiers for users and future integrations.

## Current Table Inventory

The current schema has 20 tables.

| # | Table | What it represents | Main fields |
|---|---|---|---|
| 1 | `individuals` | A person/patient-level entity. | `individual_id`, `mrn`, `external_identifier` |
| 2 | `sample_accessions` | A biological sample or specimen accession. | `sample_accession_id`, `accession_identifier`, `individual_id`, `dna_source` |
| 3 | `lab_protocols` | The physical lab method or technology used to generate data. | `lab_protocol_id`, `technology`, `manufacturer`, `miscellaneous` |
| 4 | `sample_tests` | A test performed on a sample. Links a sample to a lab protocol. | `sample_test_id`, `sample_accession_id`, `lab_protocol_id`, `test_type` |
| 5 | `pipelines` | The software/parser/process used to produce a result. | `pipeline_id`, `software_name`, `software_version`, `settings_used` |
| 6 | `source_files` | One row per imported input file. Tracks file provenance, pipeline, import status, row count, and notes. | `source_file_id`, `file_name`, `file_path`, `pipeline_id`, `imported_at`, `import_status`, `row_count`, `notes` |
| 7 | `sample_test_results` | Result-level provenance for a sample test. Links the test to a pipeline and source file. | `sample_test_result_id`, `sample_test_id`, `pipeline_id`, `source_file_id`, `genome_build`, `calling_method`, `raw_iscn`, `annotation_names` |
| 8 | `karyotypes` | ISCN/karyotype-level records derived from or preserving ISCN text. | `karyotype_id`, `sample_test_result_id`, `karyotype_text`, `clone_number`, `cell_count`, `abnormalities` |
| 9 | `genomic_events` | Deprecated compatibility table from earlier prototypes. New Phase 2.7 imports do not require or write this table. | `event_id`, `sample_test_result_id`, `source_file_id`, `event_group_id`, `event_type`, `genome_build`, `calling_method`, `raw_event_text`, `event_status` |
| 10 | `genomic_event_groups` | Deprecated compatibility table from the earlier grouping design. New imports preserve the source label directly as `event_group_id`. | `genomic_event_group_id`, `sample_test_result_id`, `event_group_label`, `event_group_type`, `raw_event_text`, `created_at` |
| 11 | `genomic_segments` | Normalized queryable genomic locations/pieces. Standalone CNVs have `event_group_id = NULL`; grouped SV pieces preserve the source `event_group_id` directly. | `segment_id`, `event_group_id`, `sample_test_result_id`, `karyotype_id`, `chromosome`, `start_pos`, `stop_pos`, `event_type`, `copy_number`, `genome_build`, `confidence`, `raw_iscn`, `raw_segment_text`, `annotations` |
| 12 | `segment_annotations` | Queryable child rows for extra CNV/SV source-file fields that are not core segment columns. | `annotation_id`, `segment_id`, `annotation_name`, `text_value`, `numeric_value`, `boolean_value`, `value_type`, `source_column`, `ordinal_position` |
| 13 | `genomic_links` | Global/direct relationships between two genomic segments for translocations, inversions, insertions, derivatives, rings, and complex events. | `link_id`, `event_group_id`, `source_segment_id`, `target_segment_id`, `link_type`, `orientation`, `evidence`, `confidence` |
| 14 | `validation_issues` | Import, parsing, or data-quality issues captured during processing. | `validation_issue_id`, `segment_id`, `issue_type`, `issue_message`, `severity` |
| 15 | `variant_classifications` | Classification assertions for observed genomic segments. | `classification_id`, `segment_id`, `classification_label`, `guideline_system`, `guideline_version`, `evidence_score`, `evidence_summary`, `review_status`, `is_current` |
| 16 | `signed_out_calls` | Case-level clinical conclusion and report/sign-out decision for a classified segment. | `signed_out_call_id`, `segment_id`, `classification_id`, `individual_id`, `sample_test_result_id`, `clinical_significance`, `interpretation_text`, `signed_out_status`, `report_text` |
| 17 | `notes` | Typed human notes attached to segments, classifications, or signed-out calls. | `note_id`, `target_table`, `target_id`, `note_type`, `note_text`, `author`, `created_at` |
| 18 | `small_variants` | Normalized SNV/indel variant identities imported from VCF files. | `small_variant_id`, `chromosome`, `position`, `variant_id`, `ref_allele`, `alt_allele`, `variant_type`, `genome_build`, `normalized_key` |
| 19 | `small_variant_sample_calls` | Per-sample genotype/call details for each imported VCF variant. | `small_variant_call_id`, `small_variant_id`, `sample_test_result_id`, `qual`, `filter_status`, `genotype`, `ref_depth`, `alt_depth`, `total_depth`, `allele_balance`, `format_keys`, `sample_values` |
| 20 | `small_variant_annotations` | Parsed functional/population/clinical annotations for small variants when present in VCF INFO fields. | `small_variant_annotation_id`, `small_variant_id`, `gene`, `transcript`, `consequence`, `impact`, `hgvs_c`, `hgvs_p`, `annotation_source`, `annotation_version` |

The schema also includes indexes for common lookups:

```text
idx_segments_region
idx_segments_event
idx_segments_event_group
idx_links_event
```

The region index is on:

```text
genomic_segments(chromosome, start_pos, stop_pos)
```

This helps genomic interval queries such as "find all segments overlapping chr5:70,000,000-150,000,000."

## Core Relationship Model

The main flow is:

```text
individuals
  -> sample_accessions
  -> sample_tests
  -> sample_test_results
  -> genomic_segments
```

Optional grouping is stored directly as the source `event_group_id` label:

```text
genomic_segments.event_group_id = TXG001
genomic_links.event_group_id = TXG001
```

This label is optional. Standalone CNVs such as `DEL`, `DUP`, `GAIN`, `LOSS`, and `AMP` normally have `genomic_segments.event_group_id = NULL`.

Additional provenance tables attach to that flow:

```text
sample_tests
  -> lab_protocols

sample_test_results
  -> pipelines
```

ISCN-derived records add:

```text
sample_test_results
  -> karyotypes
  -> genomic_segments
```

Array-derived records skip karyotypes:

```text
sample_test_results
  -> genomic_segments
```

For array-derived records, `genomic_segments.karyotype_id` is allowed to be `NULL`.

Global/direct links are separate from the provenance path:

```text
                 genomic_segments
              segment_id   segment_id
                  ^             ^
                  |             |
source_segment_id |             | target_segment_id
                  |             |
              genomic_links
```

There is only one `genomic_segments` table. The two labels mean the same table is joined twice: once as the source segment and once as the target segment. This is the structure needed for Circos-style plots. The plot should start from `genomic_links`, join directly to the source and target `genomic_segments`, and filter `link_type = 'TRANSLOCATION'` when only translocation arcs are needed. It does not need to traverse `genomic_events` or `genomic_event_groups`.

`genomic_events` and `genomic_event_groups` remain in the schema only as deprecated compatibility tables for older prototype databases. New imports should use `genomic_segments.event_group_id` and `genomic_links.event_group_id` directly.

Phase 2.6 clinical decision records attach after a segment exists:

```text
genomic_segments
  -> variant_classifications
  -> signed_out_calls
```

Typed notes can attach to several review targets:

```text
notes.target_table + notes.target_id
  -> genomic_segments
  -> variant_classifications
  -> signed_out_calls
```

## Table Responsibilities

### `individuals`

Represents a person/patient-level entity. It currently stores surrogate identity plus optional clinical/external identifiers.

### `sample_accessions`

Represents a biological sample. It stores the real accession identifier in:

```text
accession_identifier
```

This is the field used by the importer for values like `SIM001`.

### `lab_protocols`

Represents the physical laboratory method or technology used to generate data.

Examples:

```text
Karyotype
SNP Array
NGS
```

This answers: "What lab method produced this test?"

### `sample_tests`

Represents a test performed on a sample. It links:

```text
sample_accession_id
lab_protocol_id
test_type
```

Examples of `test_type`:

```text
ISCN
Array
NGS
```

### `pipelines`

Represents the software, parser, or processing pipeline used to produce a result.

Examples:

```text
ISCN Parser
Array CNV Caller
Manual Review
```

This answers: "What software or process generated/interpreted the result?"

### `source_files`

Stores one row for each imported file. This answers: "Which file was imported, where was it located, which pipeline handled it, and did the import complete cleanly?"

`import_status` uses:

```text
SUCCESS
PARTIAL_SUCCESS
FAILED
```

`SUCCESS` means all rows imported without validation issues. `PARTIAL_SUCCESS` means at least one usable row imported, but one or more rows were rejected or flagged. `FAILED` means no usable rows imported or the file-level import failed.

The official CNV import template is [templates/official_cnv_import_template.tsv](templates/official_cnv_import_template.tsv). Import files should provide `sample_id` or an accepted sample/accession alias. They should not provide `sample_test_result_id`; that identifier is generated internally when H2 creates the result row.

### `sample_test_results`

Represents the output of running a pipeline on a sample test. This is the main result-provenance table.

It stores:

```text
sample_test_id
pipeline_id
source_file_id
genome_build
calling_method
raw_iscn
annotation_names
```

This table is intentionally not just a segment table. It records result-level context before normalized events are created.

Important relationship:

```text
sample_test_results.pipeline_id
  -> pipelines.pipeline_id
```

Source file provenance is reached through:

```text
sample_test_results.source_file_id
  -> source_files.source_file_id
```

The lab protocol is reached through:

```text
sample_test_results.sample_test_id
  -> sample_tests.sample_test_id
  -> sample_tests.lab_protocol_id
  -> lab_protocols.lab_protocol_id
```

### `karyotypes`

Represents parsed or preserved karyotype/ISCN-level information. For this phase, raw ISCN strings are preserved and linked to ISCN-derived genomic segments.

### `genomic_segments`

Represents normalized queryable genomic intervals.

Important fields:

```text
segment_id
event_group_id
sample_test_result_id
karyotype_id
chromosome
start_pos
stop_pos
event_type
copy_number
genome_build
confidence
raw_iscn
raw_segment_text
annotations
```

The segment always links back to `sample_test_results`. It can optionally preserve a source `event_group_id` value such as `TXG001`, `INVG001`, or `DERG001` when the source file says multiple rows belong to the same biological event. If it came from ISCN/karyotype interpretation, it also links to `karyotypes`.

CNV size is calculated from coordinates as `stop_pos - start_pos + 1` in search and GUI output. It is not stored as a duplicate column.

### `segment_annotations`

Extra source-file fields are stored here as queryable child rows. This is the new searchable source of truth for assay-specific fields such as `Gene`, `Class`, `Lumpy`, `CNVNATOR`, `Clinical`, `DGV`, `gnomAD_version`, `probe_count`, `LRR`, `BAF`, and `array_platform`.

Core fields stay in `genomic_segments`. Extra non-core columns become `segment_annotations` rows:

```text
segment_id
annotation_name
text_value
numeric_value
boolean_value
value_type
source_column
ordinal_position
```

Blank annotation values are skipped in `segment_annotations`. The older compatibility fields remain in place: `sample_test_results.annotation_names` stores the ordered extra-column names for the result, and `genomic_segments.annotations` stores the matching pipe-delimited row values. New annotation search should use `segment_annotations`, not string parsing of the pipe fields.

Example query:

```sql
SELECT gs.*
FROM genomic_segments gs
JOIN segment_annotations sa
  ON sa.segment_id = gs.segment_id
WHERE sa.annotation_name = 'Gene'
  AND sa.text_value = 'FCGR3A';
```

### `genomic_events`

Deprecated compatibility table from earlier prototypes. New Phase 2.7 imports no longer write `genomic_events`; the active structural-event model is:

```text
segment = where
event_group_id = source label shared by linked rows
link = relationship between two locations
```

For simple CNVs, the current importer creates:

```text
one genomic_segments row
event_group_id = NULL
no genomic_links row
```

For linked events, such as generic translocation-style rows, the model is:

```text
two or more genomic_segments rows
same event_group_id stored directly on those segments
one or more genomic_links rows
same event_group_id stored directly on those links
```

This keeps the model flat. A segment remains a place on the genome, and a link stores the explicit relationship between two places.

### `genomic_event_groups`

Deprecated compatibility table from the earlier grouping design. New imports do not need this table to ask, "which segments belong to the same biological event?" Use `genomic_segments.event_group_id` or `genomic_links.event_group_id` instead.

Important fields:

```text
genomic_event_group_id
sample_test_result_id
event_group_label
event_group_type
raw_event_text
created_at
```

If the input file includes `EVENT_GROUP_ID` or `event_group_id`, that value is stored directly as `event_group_id` on `genomic_segments` and, when links are created, on `genomic_links`.

If the input file leaves the group blank, the segment is treated as a standalone CNV segment and `genomic_segments.event_group_id` stays `NULL`.

The Phase 2.7 importer supports multi-row grouping for breakpoint and structural events:

```text
event_group_id
event_type = TRANS, T, INV, INS, DER, DIC, RING, or COMPLEX
```

Rows with the same result and `event_group_id` can be linked together. The importer creates `genomic_links` rows with mapped link types such as `TRANSLOCATION`, `INVERSION`, `DERIVATIVE`, `RING`, and `COMPLEX`. Common aliases such as `group_id`, `variant_id`, `pair_id`, `link_id`, and `breakend_id` are accepted for `event_group_id`.

### `genomic_links`

Represents explicit relationships between two genomic segments.

Examples:

```text
translocation: chr9 breakpoint linked to chr22 breakpoint
inversion: left breakpoint linked to right breakpoint on the same chromosome
insertion: source material linked to destination location
derivative chromosome: multiple segments linked under one complex event
```

`genomic_links` points directly to the two related `genomic_segments` through `source_segment_id` and `target_segment_id`. It also stores the source `event_group_id`, link type, evidence/raw ISCN text, and confidence directly. The generated `output/circos_links.tsv` report starts from `genomic_links` and outputs source and target chromosome coordinates in a shape that can be converted into a Circos link file.

For translocation-only Circos links, use the direct link model:

```sql
SELECT
    src.chromosome AS source_chromosome,
    src.start_pos AS source_start,
    src.stop_pos AS source_stop,
    tgt.chromosome AS target_chromosome,
    tgt.start_pos AS target_start,
    tgt.stop_pos AS target_stop,
    gl.link_type
FROM genomic_links gl
JOIN genomic_segments src ON src.segment_id = gl.source_segment_id
JOIN genomic_segments tgt ON tgt.segment_id = gl.target_segment_id
WHERE gl.link_type = 'TRANSLOCATION';
```

### `validation_issues`

Stores import, parsing, and data-quality problems.

Examples:

```text
Missing Genome Build
Missing Chromosome
Missing Start Position
Missing End Position
Invalid Interval
Unknown Event Type
Unparseable Row
Marker Chromosome Event
Uncertain Breakpoint
Mosaic Karyotype
Annotation Count Mismatch
Import Failure
```

The goal is to preserve problems visibly instead of dropping bad records silently. `ERROR` means the row was rejected and no `genomic_segments` row was inserted. `WARNING` means the row was imported but flagged for review.

### `variant_classifications`

Stores the classification assertion for an observed segment. This is where Phase 2.6 stores the controlled label and evidence summary without putting human review state directly into `genomic_segments`.

Fields:

```text
classification_id
segment_id
classification_label
guideline_system
guideline_version
evidence_score
evidence_summary
classified_by
classified_at
review_status
is_current
supersedes_classification_id
```

`classification_id` is the unique row ID for the classification assertion. `segment_id` points back to the genomic finding being classified.

Allowed `classification_label` values:

```text
Pathogenic
Likely Pathogenic
VUS
Likely Benign
Benign
```

Allowed `review_status` values for classifications:

```text
Draft
In review
Needs more evidence
Ready for sign-out
Signed out
Superseded
```

### `signed_out_calls`

Stores the case-level clinical conclusion and sign-out/reporting decision for a classified segment. Phase 2.6 keeps this table direct and practical; a richer `clinical_interpretations` table can be added later if Phase 3 needs multi-variant, phenotype-linked, or case-level interpretation records.

Fields:

```text
signed_out_call_id
segment_id
classification_id
individual_id
sample_test_result_id
clinical_significance
relevance_to_indication
interpretation_text
signed_out_status
signed_out_by
signed_out_at
report_text
report_version
amended_from_signed_out_call_id
```

`signed_out_call_id` is the unique row ID for the sign-out record. The foreign keys keep the clinical conclusion tied to the observed segment, classification assertion, individual, and source test result.

Allowed `clinical_significance` values:

```text
Diagnostic
Carrier
Incidental
Unclear relevance
Not clinically relevant
```

Allowed `signed_out_status` values:

```text
Not started
In review
Signed out
Amended
Retracted
```

### `notes`

Stores typed human-authored notes. This table is intentionally not named `annotations`, because imported pipeline/source-file annotations are already represented by `sample_test_results.annotation_names` and `genomic_segments.annotations`.

Fields:

```text
note_id
target_table
target_id
note_type
note_text
author
created_at
```

`note_id` is the unique row ID for the note itself. `target_table` and `target_id` identify what the note is attached to. In the demo data, `note_id` and `target_id` often match because one note is created for each sign-out call in the same order, but they are different concepts. Multiple notes can point to the same target, and notes can also attach to different target tables.

Example:

```text
note_id  target_table             target_id  note_type
101      signed_out_calls         1          Follow-up note
102      signed_out_calls         1          Evidence note
103      variant_classifications  1          Reviewer note
```

This means notes 101 and 102 both belong to `signed_out_calls.signed_out_call_id = 1`, while note 103 belongs to `variant_classifications.classification_id = 1`.

Allowed `note_type` values:

```text
Reviewer note
Evidence note
Sign-out note
Import note
Follow-up note
```

## Phase 2.6 Clinical Decision Input

A sample clinical decision file is included at:

```text
data/clinical/phase26_clinical_decisions.tsv
```

This file is imported after the genomic segments already exist. For example, on a fresh scratch database:

```text
./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="data/example.cnv output/phase26-demo jdbc:h2:file:./output/phase26_demo"
./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="clinical-import data/clinical/phase26_clinical_decisions.tsv --jdbc-url jdbc:h2:file:./output/phase26_demo --output-dir output/phase26-demo"
```

When importing the whole `data` directory, files under `data/clinical/` are imported after genomic segments are loaded, so the Phase 2.6 table counts are included in the generated `import_summary.txt`.

The clinical TSV references existing `segment_id` values and stores:

```text
classification_label
evidence_summary
review_status
clinical_significance
interpretation_text
signed_out_status
report_text
note_type
note_text
```

## Import Design

The importer is split into two layers:

```text
CnvParser
CnvImportService
```

### `CnvParser`

`CnvParser` is an interface for reading a file and returning parsed records. The current implementation is:

```text
TsvCnvParser
```

This is deliberately pluggable. Later, additional parsers can be added for vendor-specific or assay-specific formats without rewriting database insertion logic.

### `CnvImportService`

`CnvImportService` handles the database workflow:

1. Parse the input file.
2. Validate required fields.
3. Create or find the individual.
4. Create or find the sample accession.
5. Create a sample test.
6. Create or find the lab protocol.
7. Create or find the pipeline.
8. Create a sample test result.
9. Reject hard-invalid rows before inserting genomic coordinates.
10. Create a karyotype if raw ISCN is present.
11. Create a genomic segment.
12. Create warning-level validation issues for unknown or complex event types that need review.

Hard reject examples:

```text
Missing Genome Build
Missing Chromosome
Missing Start Position
Missing End Position
Invalid Interval
Unparseable Row
```

Import-and-flag examples:

```text
Unknown Event Type
Marker Chromosome Event
Uncertain Breakpoint
Mosaic Karyotype
Annotation Count Mismatch
```

`ADD` and `DER` are supported event types and are stored as `event_type = ADD` or `event_type = DER` when detected. Mosaic ISCN is treated as a warning/flag while the underlying event type, such as `DEL` or `DUP`, takes precedence. Marker chromosome (`MAR`) is not treated as a supported parsed segment event in Phase 2.5; if coordinates and genome build are still usable, the segment is stored as `event_type = UNKNOWN` with a warning. If the row cannot be placed in the genome, it is rejected by the normal hard-reject rules.

Annotation mismatch is metadata quality, not core CNV placement. If chromosome, start, end, event type, and genome build are usable, the segment is imported and `Annotation Count Mismatch` is recorded as a `WARNING`.

The importer does not automatically correct or rewrite ISCN strings.

## Assay-Specific Annotation Handling

The CNV/SV importer stores common, frequently queried fields in dedicated `genomic_segments` columns and stores assay-specific leftovers in `segment_annotations`.

Dedicated fields are not duplicated into annotations. Current dedicated fields include:

```text
chromosome
start_pos
stop_pos
event_type
copy_number
genome_build
confidence
event_group_id
raw_iscn
```

All remaining assay-specific fields are inserted as queryable child rows in:

```text
segment_annotations
```

For example:

```text
segment_id | annotation_name | text_value | numeric_value | boolean_value
101        | Gene            | FCGR3A     | NULL          | NULL
101        | Clinical        | CDP2       | NULL          | NULL
101        | Lumpy           | NULL       | 1             | NULL
101        | CNVNATOR        | NULL       | 1             | NULL
```

Blank annotation values are skipped in `segment_annotations` because matching no longer depends on positional pipe parsing.

The older compatibility fields remain in place:

```text
sample_test_results.annotation_names
genomic_segments.annotations
```

Those pipe-delimited fields preserve historical import output, but new search logic should join against `segment_annotations`.

NGS-derived CNV/SV files keep caller- and population-resource fields as annotations, such as `Gene`, `Clinical`, `Lumpy`, `CNVNATOR`, `Gnomad_Length`, `Gnomad_Percent_Overlap`, `DGV_Pop_Percent`, `Exclude_Length`, `Stitched`, and `gnomAD_version`.

Array-derived CNV files keep platform- and assay-specific fields as annotations, such as `ProbeCount`/`NumProbes`, `ArrayScore`, `log2_ratio`, `baf_pattern`, `lrr_value`, `snp_count`, `roh_status`, `gene_count`, `array_platform`, `array_design`, and `call_algorithm`. Confidence is stored in the dedicated `genomic_segments.confidence` column.

If an input file provides explicit `annotation_names` and `annotations`, the importer still filters out names that map to dedicated fields and preserves the remaining name/value pairs in compatibility output while also inserting nonblank values into `segment_annotations`.

## VCF Small Variants

VCF/SNV/indel import uses a separate branch of the schema and does not write to `genomic_segments`, `segment_annotations`, or `genomic_links`.

The CLI directory import auto-detects VCF files by `.vcf`, `.vcf.txt`, `##fileformat=VCF`, or `#CHROM`, then routes them to `VcfImportService`. GUI VCF import is intentionally not enabled yet.

VCF data is stored like this:

```text
small_variants
small_variant_sample_calls
small_variant_annotations
```

`small_variants` stores one row per normalized REF/ALT variant. Multi-ALT VCF rows are split into separate variant rows. `small_variant_sample_calls` stores one row per sample per split variant, including genotype, phasing, QUAL/FILTER, depth fields, allele balance, raw FORMAT keys, raw sample values, raw INFO, and the original VCF line. `small_variant_annotations` stores parsed annotation records from supported INFO annotations such as SnpEff `ANN`, `LOF`, and `NMD`.

Genome build detection is required. The importer checks, in order:

```text
1. ##reference=
2. ##assembly=
3. VCF metadata and command-line reference filenames
4. user/default import parameter
5. otherwise reject the rows with Missing Genome Build
```

Known aliases are normalized, including:

```text
hg19 / build37 / GRCh37 -> GRCh37
hg38 / assembly38 / Homo_sapiens_assembly38.fasta / GRCh38 -> GRCh38
```

Example query:

```sql
SELECT sv.*, svc.genotype, svc.alt_depth
FROM small_variants sv
JOIN small_variant_sample_calls svc
  ON svc.small_variant_id = sv.small_variant_id
JOIN small_variant_annotations sva
  ON sva.small_variant_id = sv.small_variant_id
WHERE sva.gene = 'TP53'
  AND sv.genome_build = 'GRCh38';
```

Terminal VCF search is available through:

```bash
java -cp target/classes:/path/to/h2.jar org.mpgdatabase.App vcf-search --jdbc-url jdbc:h2:file:./output/mpg_database_h2 --gene OR4F5
```

Supported `vcf-search` filters:

```text
--sample
--chromosome
--start
--stop / --end
--variant-id
--variant-type
--genome-build
--genotype
--filter-status
--gene
--consequence
--impact
--min-alt-depth / --alt-depth-min
--max-alt-depth / --alt-depth-max
--min-total-depth / --total-depth-min
--max-total-depth / --total-depth-max
--min-allele-balance / --allele-balance-min
--max-allele-balance / --allele-balance-max
```

## File-Type Detection

Phase 2.5 detects calling method in this order:

```text
1. Manual override
2. Header metadata if present
3. Required/optional columns
4. File name pattern
5. Unknown
```

The current calling methods are:

```text
ISCN-derived
Array-derived
SNP-array-derived
NGS-derived
Generic CNV
Unknown
```

Current detection examples:

```text
raw_iscn or karyotype_text present -> ISCN-derived
array_score or number_of_sites present -> Array-derived
BAF/LRR/probe_count present -> SNP-array-derived
read_depth/coverage/bin_count present -> NGS-derived
Lumpy/CNVNATOR/SV_Type/hg_version present -> NGS-derived
only chr/start/stop/event/copy number present -> Generic CNV
cannot determine -> Unknown + validation warning
```

The importer also does row-level refinement. This matters for mixed files: a row with populated `raw_iscn` is treated as ISCN-derived, while a row with blank `raw_iscn` and array fields can still be treated as array-derived.

## Current CNV Test Format

The current parser supports TSV-like files ending in `.cnv`, `.tsv`, or `.txt`.

Minimum required columns:

```text
sample_accession_id
chromosome
start_pos
stop_pos
event_type
genome_build
```

`copy_number` is preferred, but it can be inferred for common called CNV event types:

```text
DEL / LOSS -> 1
DUP / GAIN / AMP -> 3
ROH / UPD / NEUTRAL -> 2
```

Optional columns:

```text
copy_number
raw_iscn
dna_source
```

Final called aCGH and SNP-array CNV interval files are supported. This phase does not import raw array probe, manifest, or evidence rows as CNV segments.

Common aCGH/SNP-array aliases are mapped into structured fields or annotations:

```text
Sample / SampleID / sample_id -> sample_accession_id
Chr / Chromosome -> chromosome
Start / BP1 / Start_Position -> start_pos
End / Stop / BP2 / End_Position -> stop_pos
Type / CNV_Type / Aberration / EventType -> event_type
CopyNumber / Copy_Number / CN -> copy_number
GenomeBuild / Genome_Build / Build / Assembly / hg_version -> genome_build
Confidence / ConfidenceScore / CallConfidence -> confidence
ProbeCount / NumProbes / NumberOfProbes / NumberOfSites -> annotation
ArrayScore / Array_Score / Score / CNVScore -> annotation
```

aCGH-style fields such as `LogRatio` or `MeanLogRatio` help detect `Array-derived` files. SNP-array-style fields such as `MeanBAF`, `MeanLRR`, `LOHScore`, or `ROHScore` help detect `SNP-array-derived` files. Extra columns such as `Gene`, `Cytoband`, `Classification`, `Clinical`, `DGV`, `QCFlag`, `MeanBAF`, and `MeanLRR` are preserved in `annotation_names` and `annotations`.

Raw probe/evidence-only files with columns such as `ProbeName`, `SystematicName`, `PValueLogRatio`, `gProcessedSignal`, `rProcessedSignal`, `AlleleA`, `AlleleB`, `BAF`, `LRR`, or `MapInfo` are rejected for CNV segment import unless they also contain a called CNV interval and event type. The importer records `Unsupported Array Evidence File` in `validation_issues`.

The parser also supports a WGS/NGS classified SV result table with columns such as:

```text
Sample
Chr
Start
End
SV_Type
hg_version
Gene
Class
Lumpy
CNVNATOR
Clinical
gnomAD_version
```

For this file shape:

```text
Sample -> sample_accession_id
Chr -> chromosome
Start -> start_pos
End -> stop_pos
SV_Type -> event_type
hg_version -> genome_build
```

When a true copy-number column is absent, NGS DEL/DUP rows infer copy number:

```text
DEL -> copy_number = 1
DUP -> copy_number = 3
```

All non-core WGS columns are stored as generic annotations.

Example:

```text
sample_accession_id	chromosome	start_pos	stop_pos	event_type	copy_number	array_score	number_of_sites	genome_build	raw_iscn
SIM001	chr5	75000000	120000000	DEL	1	0.91	421	hg38	46,XX,del(5)(q13q33)
```

## Genome Build Resolution And Normalization

Genome build is resolved in this order:

1. `genome_build` column in the record
2. file metadata header, for example:

```text
# genome_build=hg38
```

3. importer default genome build
4. unresolved

After resolution, Phase 2.8 normalizes supported aliases to canonical stored values:

```text
hg19, Build 37, b37 -> GRCh37
hg38, Build 38, b38 -> GRCh38
T2T, CHM13, CHM13v2, CHM13v2.0 -> T2T-CHM13
hg18, Build 36, b36 -> NCBI36
```

If the build is missing, no `genomic_segments` row is inserted for that record. The importer still continues with other rows and creates a validation issue:

```text
issue_type = Missing Genome Build
severity = ERROR
```

If a build value is present but unsupported, such as `banana38`, `hg83`, `build99`, `unknown`, or `random_text`, the row is also skipped and recorded as:

```text
issue_type = Invalid Genome Build
severity = ERROR
```

Genome build is required before genomic coordinates are stored because a coordinate interval is not meaningful without its reference build. Stored result rows should contain only canonical build values such as `GRCh37`, `GRCh38`, `T2T-CHM13`, or intentionally supported legacy `NCBI36`.

## ISCN vs Array Handling

The importer currently uses the presence of `raw_iscn` to classify a record:

```text
raw_iscn present -> ISCN-derived result
raw_iscn absent  -> Array-derived result
```

For ISCN-derived records:

```text
lab_protocols.technology = Karyotype
pipelines.software_name = ISCN Parser
sample_tests.test_type = ISCN
karyotypes row is created
genomic_segments.karyotype_id is populated
```

For array-derived records:

```text
lab_protocols.technology = SNP Array
pipelines.software_name = Array CNV Caller
sample_tests.test_type = Array
no karyotype row is required
genomic_segments.karyotype_id is NULL
```

This is simple, but it preserves the distinction required by the Phase 2 design.

## Verification Workflow

Verification runs automatically after database initialization and CNV import.

The terminal summary includes:

```text
Database Initialization: PASS/FAIL

Table Counts
------------
Individuals:
Sample Accessions:
Sample Tests:
Sample Test Results:
Karyotypes:
Genomic Segments:
Validation Issues:

Import Status
-------------
example.cnv imported: PASS/FAIL
test.cnv imported: PASS/FAIL

Verification Status
-------------------
Schema Verification: PASS/FAIL
Relationship Verification: PASS/FAIL
ISCN Verification: PASS/FAIL
Array Verification: PASS/FAIL
Query Verification: PASS/FAIL
Data Integrity Verification: PASS/FAIL

Overall Result: PASS/FAIL
```

The current implementation also includes data-integrity verification for:

```text
No orphan genomic_segments
No orphan genomic_links source/target segments
No orphan karyotypes
No orphan sample_test_results
No duplicate accession identifiers
```

## Generated Reports

Manual runs generate:

```text
output/import_summary.txt
output/run_log.tsv
output/segments.tsv
output/genomic_events.tsv
output/event_groups.tsv
output/genomic_links.tsv
output/circos_links.tsv
output/segments_by_sample.tsv
output/source_files.tsv
output/sample_test_results.tsv
output/karyotypes.tsv
output/validation_issues.tsv
output/query_results.txt
output/result_trace.tsv
```

These reports are verification artifacts, not a full user-facing export layer.

`genomic_events.tsv` is a legacy compatibility export. New imports do not write row-level `genomic_events`.

`event_groups.tsv` summarizes direct `event_group_id` labels from `genomic_segments`, not rows from the deprecated `genomic_event_groups` table.

`run_log.tsv` is the run-level operational log. It records each genomic import, clinical decision import, and verification step in a structured TSV format.

```text
timestamp
step
file_name
status
records_seen
segments_inserted
classifications_inserted
signed_out_calls_inserted
notes_inserted
issues_inserted
message
```

`result_trace.tsv` is the most useful report for provenance review. It prints `sample_test_results` together with lab protocols, pipelines, genomic segments, and generic annotations.

```text
output/result_trace.tsv
```

with fields such as:

```text
accession_identifier
individual_id
sample_test_id
sample_test_result_id
lab_protocol
test_type
pipeline
source_file_id
source_file
import_status
source_row_number
genome_build
calling_method
raw_iscn
annotation_names
event_group_id
segment_id
chromosome
start_pos
stop_pos
event_type
copy_number
annotations
validation_issue_count
validation_summary
```

`event_groups.tsv` summarizes Phase 2.7 event bundles from direct segment labels:

```text
event_group_id
sample_accession_id
source_file_name
segment_count
chromosomes_involved
event_types
copy_numbers
regions
raw_event_text
```

## Required Queries

The verification service runs these required queries:

1. Retrieve all segments for sample `SIM001`.
2. Retrieve all segments overlapping `chr5:70,000,000-150,000,000`.
3. Retrieve all validation issues.
4. Retrieve all ISCN-derived segments.

Results are written to:

```text
output/query_results.txt
```

## How To Run

From the repository root:

```bash
./apache-maven-3.9.15/bin/mvn test
```

Run the CLI import and verification workflow:

```bash
./apache-maven-3.9.15/bin/mvn exec:java
```

Run with a manual calling-method override:

```bash
./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="data output jdbc:h2:file:./output/mpg_database_h2 --calling-method-override NGS-derived"
```

The CLI defaults to:

```text
data/example.cnv
data/test.cnv
output/
jdbc:h2:file:./output/mpg_database_h2
```

The default import workflow reads files in `data/` ending in:

```text
.cnv
.tsv
.txt
```

The file-backed database is created at:

```text
output/mpg_database_h2.mv.db
```

Tests use in-memory H2:

```text
jdbc:h2:mem:phase2;DB_CLOSE_DELAY=-1
```

## Running With Different Paths

The CLI accepts optional arguments:

```bash
./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="<dataDir> <outputDir> <jdbcUrl>"
```

Example:

```bash
./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="data output jdbc:h2:file:./output/mpg_database_h2"
```

## Flexible CLI Search

Search runs against the file-backed H2 database created by the import workflow.

Search by sample:

```bash
./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="search --sample SIM001"
```

Search with combined filters:

```bash
./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="search --sample SIM001 --event-type DEL --chromosome chr5"
```

Search by interval overlap:

```bash
./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="search --event-type DUP --chromosome chr1 --start 160000000 --stop 162000000"
```

Search by event group:

```bash
./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="search --event-group TXG001"
```

Search by legacy row-level event id, for older databases that still contain `genomic_events` links:

```bash
./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="search --event-id 101"
```

Search WGS/NGS annotations:

```bash
./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="search --calling-method NGS-derived --gene FCGR3A"
```

Search by a named generic annotation:

```bash
./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="search --annotation Gene=FCGR3A"
```

Other examples:

```bash
./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="search --annotation Class=CDP2"

./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="search --annotation Clinical=1"

./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="search --annotation Lumpy=1"
```

Multiple annotation filters can be repeated. Repeated annotation filters combine with AND:

```bash
./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="search --annotation Gene=FCGR3A --annotation Clinical=1"
```

Multiple values for the same annotation key use OR:

```bash
./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="search --annotation Gene=FCGR3A,CYP2A6"
```

Annotation filters combine with normal filters:

```bash
./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="search --annotation Gene=FCGR3A,CYP2A6 --event-type DEL,DUP"
```

Annotation matching is exact for Phase 2.5. For example, `Gene=BRCA` does not automatically match `BRCA1` or `BRCA2`.

Search multiple values:

```bash
./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="search --event-type DEL,DUP --genome-build GRCh37,GRCh38"
```

Within one filter, comma-separated values are treated as OR. Between different filters, conditions are combined with AND.

Example:

```bash
./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="search --chromosome chr5,chr7 --event-type DEL,DUP"
```

means:

```text
(chromosome = chr5 OR chromosome = chr7)
AND
(event_type = DEL OR event_type = DUP)
```

Empty comma values are ignored, and whitespace is trimmed. For example, `DEL,,DUP` and `DEL, DUP` both work.

Supported filters:

```text
--sample
--event-id
--event-group
--event-type
--chromosome
--start
--stop
--end
--calling-method
--genome-build
--confidence
--gene
--class
--annotation
--jdbc-url
```

`--gene` and `--class` currently search inside the generic `annotations` string.

## H2 Web Console

The project can start H2's built-in browser-accessible console for local database inspection.

Start it from the repository root:

```bash
./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="console"
```

The app prints a URL like:

```text
H2 Web Console: http://localhost:8082
```

Open that URL in a browser and use:

```text
JDBC URL: jdbc:h2:file:./output/mpg_database_h2
User: leave blank
Password: leave blank
```

Use a different port if `8082` is busy:

```bash
./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="console --port 8090"
```

Use a different database URL:

```bash
./apache-maven-3.9.15/bin/mvn exec:java -Dexec.args="console --jdbc-url jdbc:h2:file:./output/mpg_database_h2"
```

The console process keeps running until you stop it with `Ctrl+C`.

## Inspecting The H2 Database

The project uses H2 as a file-backed database for manual runs. If the H2 dependency has been downloaded by Maven, the H2 shell can be used directly:

```bash
java -cp ~/.m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar org.h2.tools.Shell -url jdbc:h2:file:./output/mpg_database_h2
```

Example provenance query:

```sql
SELECT
    sa.accession_identifier,
    lp.technology,
    st.test_type,
    p.software_name,
    p.software_version,
    str.genome_build,
    str.calling_method,
    str.raw_iscn,
    sf.file_name AS source_file,
    gs.segment_id,
    gs.chromosome,
    gs.start_pos,
    gs.stop_pos,
    gs.event_type
FROM sample_accessions sa
JOIN sample_tests st
    ON st.sample_accession_id = sa.sample_accession_id
JOIN lab_protocols lp
    ON lp.lab_protocol_id = st.lab_protocol_id
JOIN sample_test_results str
    ON str.sample_test_id = st.sample_test_id
JOIN pipelines p
    ON p.pipeline_id = str.pipeline_id
LEFT JOIN source_files sf
    ON sf.source_file_id = str.source_file_id
JOIN genomic_segments gs
    ON gs.sample_test_result_id = str.sample_test_result_id
ORDER BY gs.segment_id;
```

This query shows how a normalized segment traces back to the sample, lab protocol, test result, pipeline, and source input file.

## Project Layout

```text
pom.xml
README.md
data/
  example.cnv
  test.cnv
  phase27_finding_model.cnv
src/main/resources/
  schema.sql
src/main/java/org/mpgdatabase/
  App.java
  db/
    Database.java
  dao/
    CoreDao.java
    GenomicSegmentDao.java
    DaoSupport.java
  importer/
    CnvParser.java
    TsvCnvParser.java
    CnvParserFactory.java
    CnvImportService.java
    CnvRecord.java
    ParsedCnvFile.java
    ImportResult.java
  model/
    Models.java
  report/
    SearchService.java
    VerificationService.java
    VerificationReport.java
    VerificationResult.java
src/test/java/org/mpgdatabase/
  Phase2WorkflowTest.java
output/
  generated reports, imported input copies, and file-backed H2 database
```

## Implementation Notes

### Why DAOs Are Small

The DAO layer is intentionally minimal. It exists to keep SQL out of the importer and verifier, while still making SQL visible and easy to reason about.

### Why There Is No ORM

An ORM would hide some of the relationship details. For this phase, explicit SQL is useful because the project is proving the schema and relationship flow.

### Why The Importer Groups Rows Into Shared Results

The Phase 2.7 importer creates one `sample_test_result` for rows that share the same sample, lab protocol, test type, pipeline, source file, genome build, calling method, raw ISCN text, and annotation layout.

This better matches real lab results, where one test result can contain multiple findings or multiple breakpoints from one complex event. Individual source rows remain traceable through `genomic_segments`, direct `event_group_id` labels, `genomic_links`, validation issues, and report trace fields.

### Why Missing Genome Builds Are Rejected

Records with missing genome build are preserved as result-level provenance and validation issues, but they are not inserted into `genomic_segments`. This avoids storing coordinates that cannot be interpreted against a known reference build.

### Why `karyotype_id` Is Nullable On `genomic_segments`

Only ISCN-derived segments need karyotype provenance. Array-derived segments can be valid normalized genomic events without a karyotype record.

## Current Known Gaps

- No vendor-specific CNV parsers yet.
- No update/merge behavior for repeated imports.
- No PostgreSQL migration scripts yet.
- No clinical phenotype tables yet.
- No separate multi-variant clinical interpretation table yet.
- No UI/API layer in this repository.
- Generic annotations are stored as ordered strings rather than a normalized annotation table.

These are expected gaps for Phase 2.7, not accidental omissions.

## Next Good Additions

Useful next implementation steps:

- Add more DAO query methods for provenance inspection.
- Add stricter annotation-specific search instead of broad string search.
- Add CLI commands for creating and querying Phase 2.6 clinical decision records.
- Add a schema diagram to documentation.
- Add PostgreSQL-compatible migration notes.
- Replace sample fixture files with real `example.cnv` and `test.cnv` once available.

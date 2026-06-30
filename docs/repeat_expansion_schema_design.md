# Repeat Expansion Schema Design

## Purpose

This document explains the proposed repeat expansion database tables, why the tables are separated this way, what design decisions were made, and what questions still need confirmation.

The goal is to support repeat expansion results as a first-class finding type alongside SNV/indel and CNV/SV data. The schema should support patient review, cohort/group search, and the future repeat expansion visualization tool without forcing raw read data directly into the database.

## Design Principle

The repeat expansion design follows the same general philosophy as the rest of the MPG database:

- Store stable locus/reference information in a reference table.
- Store the actual patient/sample repeat finding in a finding table.
- Store extra caller-specific or source-specific fields in an annotation table.
- Keep clinical review, classification, and sign-out in shared interpretation tables.
- Do not store raw BAM/CRAM/read data inside the database.

In simple terms:

```text
repeat_loci
  = what repeat region is this?

repeat_expansions
  = what repeat result was found in this patient/sample?

repeat_expansion_annotations
  = what extra tool-specific or evidence-specific details came with the import?
```

## Proposed Tables

### repeat_loci

```text
repeat_locus_id
gene_symbol
repeat_locus_name
chromosome
start_position
end_position
genome_build
repeat_motif
known_repeat_disorder_locus
```

This table stores stable information about known repeat regions, such as FMR1, HTT, DMPK, C9orf72, FXN, and other curated repeat loci.

Why these fields belong here:

- `repeat_locus_id`: internal database ID for the repeat locus.
- `gene_symbol`: clinicians commonly search by gene.
- `repeat_locus_name`: stores the named repeat locus, such as FMR1 or HTT.
- `chromosome`, `start_position`, `end_position`: supports coordinate-based search and genome-browser display.
- `genome_build`: required because coordinates only make sense relative to a reference build.
- `repeat_motif`: stores the repeated unit, such as CAG, CGG, CTG, GAA, or GGGGCC.
- `known_repeat_disorder_locus`: supports filtering to curated clinically relevant repeat loci.

This table should not store patient-specific repeat counts. It describes the repeat region itself.

### repeat_expansions

```text
repeat_expansion_id
sample_test_result_id
repeat_locus_id
allele1_repeat_count
allele2_repeat_count
max_repeat_count
expansion_category
data_mode
quality_status
repeat_count_confidence_range
raw_data_available
repeat_visualization_available
raw_repeat_text
line_number
```

This table stores the actual repeat expansion finding for a patient/sample/test result.

Why these fields belong here:

- `repeat_expansion_id`: internal ID for the repeat finding.
- `sample_test_result_id`: links the repeat finding to the test result, source file, pipeline, genome build, and calling method.
- `repeat_locus_id`: links the finding to the known repeat locus.
- `allele1_repeat_count`, `allele2_repeat_count`: stores the main allele-level repeat counts for a simple first model.
- `max_repeat_count`: supports fast filtering for any allele above a threshold.
- `expansion_category`: stores categories such as normal, intermediate, premutation, full mutation, expanded, uncertain, or not assessed.
- `data_mode`: records whether the result came from raw reads, assembly-based evidence, targeted assay, historical report, or another mode.
- `quality_status`: stores the call-level technical quality status.
- `repeat_count_confidence_range`: stores uncertainty when counts are approximate or reported as a range.
- `raw_data_available`: simple yes/no flag indicating whether raw/read-level evidence exists.
- `repeat_visualization_available`: simple yes/no flag indicating whether a repeat plot or visualization exists.
- `raw_repeat_text`: preserves the original imported repeat text or row-level call text.
- `line_number`: helps trace the finding back to the source file row.

This table should focus on the measured repeat call itself. It should not store final clinical interpretation fields such as review status, evidence score, clinical significance, reportability, or sign-out status. Those belong in shared interpretation/sign-out tables.

### repeat_expansion_annotations

```text
repeat_annotation_id
repeat_expansion_id
annotation_name
text_value
numeric_value
boolean_value
value_type
source_column
```

This table stores extra fields from imported repeat expansion files or tools.

Examples of fields that can live here:

- spanning read count
- read support count
- partial read count
- ambiguous read count
- mean mapping quality
- motif interruptions
- methylation notes
- caller score
- QC warnings
- assembly confidence
- read-back-to-assembly QC details

Why this table exists:

Different tools and evidence modes produce different extra columns. TRGT, ExpansionHunter, assembly-based workflows, historical reports, and manual review may not all output the same fields. If every possible field became a column in `repeat_expansions`, the table would become too wide and hard to maintain.

Typed annotation values make search easier:

- `text_value` for text fields
- `numeric_value` for numbers
- `boolean_value` for true/false fields
- `value_type` to identify which value column should be used
- `source_column` to preserve the original imported column name

This mirrors the existing `segment_annotations` design and avoids string parsing.

## Small Variant Table Design

The small variant schema uses a similar principle: separate the variant itself, the sample-specific call, the functional annotation, and flexible external/future attributes.

In simple terms:

```text
small_variants
  = what is the variant?

small_variant_sample_calls
  = was this variant found in this sample, and what was the sample-level evidence?

small_variant_annotations
  = what gene/transcript/consequence annotation came from the VCF annotation field?

small_variant_attributes
  = what extra external or future attributes are attached to the variant?
```

### small_variants

```text
small_variant_id
chromosome
position
end_position
ref_allele
alt_allele
variant_type
genome_build
variant_id
normalized_key
```

This table stores the identity and genomic location of the variant. One row represents one unique SNV/indel.

Example:

```text
chr1:941119 A>G
```

This table should not store gene, transcript, consequence, genotype, VAF, or read depth. Those are different layers of information.

### small_variant_sample_calls

```text
small_variant_call_id
small_variant_id
sample_test_result_id
qual
filter_status
genotype
zygosity
phased
ref_depth
alt_depth
total_depth
genotype_quality
variant_allele_fraction
format_keys
sample_values
info_raw
line_number
```

This table stores sample-specific call evidence. The same variant can appear in many samples, and each sample may have different genotype, depth, quality, and VAF.

Example:

```text
small_variant_id = 10
sample_test_result_id = 5
genotype = 0/1
variant_allele_fraction = 0.48
total_depth = 100
```

This table answers:

```text
Was this variant observed in this sample, and how strong is the evidence?
```

### small_variant_annotations

```text
small_variant_annotation_id
small_variant_id
gene
gene_id
transcript
is_preferred_transcript
consequence
impact
hgvs_c
hgvs_p
annotation_source
annotation_version
annotation_raw
```

This table stores structured functional annotations from VCF annotation tools such as SnpEff, VEP, or ANNOVAR.

These fields usually come from the row-level VCF `INFO` column, especially an annotation field such as:

```text
ANN=...
```

For example:

```text
ANN=G|missense_variant|MODERATE|OR4F5|ENSG00000186092|transcript|ENST00000641515.2|...|c.484A>G|p.Thr162Ala|...
```

This becomes:

```text
gene = OR4F5
consequence = missense_variant
impact = MODERATE
transcript = ENST00000641515.2
hgvs_c = c.484A>G
hgvs_p = p.Thr162Ala
```

These fields should not be moved into `small_variants` or `small_variant_sample_calls`.

Reason:

One variant can have multiple `ANN` annotations.

Example:

```text
chr1:941119 A>G
ANN = NOC2L downstream_gene_variant
ANN = SAMD11 intron_variant
```

That means one variant has two annotation interpretations:

```text
gene = NOC2L
consequence = downstream_gene_variant
```

and:

```text
gene = SAMD11
consequence = intron_variant
```

If `gene`, `transcript`, `consequence`, `impact`, `hgvs_c`, and `hgvs_p` were stored directly in `small_variants`, the database would have to choose one annotation or store multiple values in one text field. Both are problematic. Choosing one loses information. Storing comma-delimited values makes searching unreliable.

If those fields were stored in `small_variant_sample_calls`, they would be attached to the sample call even though they describe the variant/transcript, not the sample-specific genotype, depth, or VAF.

So the cleaner design is:

```text
small_variants
  = one row per genomic variant

small_variant_sample_calls
  = one row per sample-specific call

small_variant_annotations
  = one row per transcript/gene consequence annotation
```

This lets one variant have multiple annotation rows while keeping common clinician searches fast and clear.

### small_variant_attributes

```text
attribute_id
small_variant_id
annotation_name
text_value
numeric_value
boolean_value
value_type
source_name
source_version
population
subpopulation
evidence_url
raw_value
```

This table stores flexible external or future attributes that should not become dedicated columns unless they become common clinician filters.

Examples:

```text
source_name = ClinVar
annotation_name = clinical_significance
text_value = Pathogenic
```

```text
source_name = gnomAD
annotation_name = allele_frequency
numeric_value = 0.0002
population = global
```

```text
source_name = dbSNP
annotation_name = rsID
text_value = rs28705211
```

This table is intentionally flexible because external resources can vary widely. ClinVar, gnomAD, dbSNP, OMIM, CADD, SpliceAI, UniProt, PubMed, and future sources may all provide different kinds of values.

The design keeps frequently searched functional fields in `small_variant_annotations`, while leaving source-specific external attributes in a typed key/value table.

## Case Table Design

The `cases` table was added to separate clinical context from the physical sample, the accession, the test, and the result file.

In simple terms:

```text
individuals
  = who is the patient?

samples
  = what biological specimen was collected?

sample_accessions
  = what lab accession/order identifier was assigned?

sample_tests
  = what test was performed?

sample_test_results
  = what result/import/output was produced?

cases
  = why was testing done, and what clinical context does it belong to?
```

Recommended fields:

```text
case_id
individual_id
diagnosis
clinical_indication
phenotype_summary
case_type
case_status
opened_date
closed_date
```

The `cases` table should link to `individuals`:

```text
cases.individual_id -> individuals.individual_id
```

Then `sample_tests` can link to `cases`:

```text
sample_tests.case_id -> cases.case_id
```

That gives this structure:

```text
individual
  -> case
  -> sample_test
  -> sample_test_result
  -> findings
```

and also:

```text
individual
  -> sample
  -> sample_accession
  -> sample_test
  -> sample_test_result
  -> findings
```

### Why not put diagnosis/phenotype/case status in sample?

The sample is the physical specimen, such as blood, bone marrow, saliva, tumor, cultured cells, or extracted DNA. A physical specimen does not inherently have a diagnosis or indication. The clinical case/order does.

Example:

```text
sample = bone marrow collected 2026-06-01
case = leukemia workup, pending review
```

If diagnosis, indication, phenotype, and case status were stored in `samples`, the database would mix specimen facts with clinical case facts.

### Why not put diagnosis/phenotype/case status in sample_test_result?

One test can have multiple result imports or reanalyses.

Example:

```text
sample_test = WGS for developmental delay
sample_test_result 1 = first pipeline run
sample_test_result 2 = reanalysis
sample_test_result 3 = updated import
```

If diagnosis and indication lived in `sample_test_results`, the same clinical context would be repeated three times. That creates a risk that one row is updated and another is not.

### Why not put diagnosis/phenotype/case status only in sample_test?

This is better than putting them in `sample_test_result`, but it still repeats context when one clinical case has multiple tests.

Example:

```text
case = developmental delay
  sample_test 1 = microarray
  sample_test 2 = WES
  sample_test 3 = repeat expansion testing
```

If the case context lived only in `sample_tests`, the same diagnosis and phenotype would be repeated across each test.

### What difference does the cases table make?

The `cases` table gives one place for clinical context:

```text
diagnosis
clinical_indication
phenotype_summary
case_type
case_status
```

This helps patient and cohort search because filters such as diagnosis, phenotype, case type, and case status can join through one clear table.

It also supports longitudinal workflows where one patient has multiple cases over time:

```text
Patient 1
  Case A: developmental delay / constitutional testing
  Case B: leukemia / oncology testing
  Case C: repeat expansion concern / neurology testing
```

## Interpreted Calls Table Design

The `interpreted_calls` table was added as a shared bridge between raw/called finding tables and the clinical interpretation tables.

In simple terms:

```text
genomic_segments
small_variant_sample_calls
repeat_expansions
  = called data layer

interpreted_calls
  = shared handle for a finding that can be interpreted

variant_classifications
  = classification/review layer

signed_out_calls
  = clinical sign-out/report layer
```

Recommended fields:

```text
interpreted_call_id
finding_type
finding_id
sample_test_result_id
individual_id
created_at
```

What the fields mean:

- `interpreted_call_id`: internal ID used by classification, sign-out, and notes tables.
- `finding_type`: identifies what kind of finding is being interpreted, such as `SMALL_VARIANT_SAMPLE_CALL`, `GENOMIC_SEGMENT`, `REPEAT_EXPANSION`, or `KARYOTYPE`.
- `finding_id`: the ID of the row in the source finding table.
- `sample_test_result_id`: links the interpreted call back to the result/import context.
- `individual_id`: links the interpreted call to the patient for easier lookup.
- `created_at`: records when the interpreted-call wrapper was created.

Example:

```text
interpreted_call_id = 100
finding_type = REPEAT_EXPANSION
finding_id = 25
sample_test_result_id = 7
individual_id = 3
```

This means:

```text
Interpreted call 100 is the clinical interpretation handle for repeat_expansion_id 25.
```

### Why not put classification fields directly on each finding table?

Without `interpreted_calls`, each finding table would need its own review/classification/sign-out fields:

```text
genomic_segments.review_status
small_variant_sample_calls.review_status
repeat_expansions.review_status
```

or the classification table would need many nullable foreign keys:

```text
variant_classifications.segment_id
variant_classifications.small_variant_call_id
variant_classifications.repeat_expansion_id
```

That becomes messy as more finding types are added.

With `interpreted_calls`, all finding types can share the same interpretation system:

```text
interpreted_calls
  -> variant_classifications
  -> signed_out_calls
```

### What difference does interpreted_calls make?

It lets the database use one shared classification/sign-out workflow for:

```text
SNV/Indel
CNV/SV
Repeat Expansion
Karyotype/ISCN
```

It also keeps the called data separate from clinical interpretation.

For example:

```text
small_variant_sample_calls
  = genotype, VAF, depth, filter status

variant_classifications
  = pathogenic/VUS/benign, evidence summary, review status

signed_out_calls
  = reportability, clinical significance, report text, signed-out status
```

This is important because the same called finding may be reinterpreted later. The original call should remain traceable, while classifications and sign-outs can be updated, superseded, or amended.

The table also gives `notes` a clean target:

```text
notes.target_table = interpreted_calls
notes.target_id = interpreted_call_id
```

That lets human review notes attach to the interpreted finding regardless of whether the finding is an SNV, CNV/SV, repeat expansion, or karyotype finding.

## How This Supports the Repeat Expansion Visualization Tool

The repeat expansion visualization design spec separates repeat expansion review into several layers:

```text
raw evidence
called repeat result
known locus/catalog annotation
clinical decision/review layer
```

The proposed schema maps to those layers:

```text
repeat_loci
  = known locus/catalog annotation

repeat_expansions
  = called repeat result

repeat_expansion_annotations
  = extra evidence/QC/caller fields

variant_classifications and signed_out_calls
  = clinical decision/review layer
```

The schema supports common visualization questions:

- What repeat locus is this?
- What gene is involved?
- What motif is repeated?
- What are the allele repeat counts?
- Is the repeat normal, intermediate, premutation, full mutation, expanded, or uncertain?
- Did the result come from raw reads, assembly, targeted assay, or historical report?
- Is raw evidence available?
- Is a repeat visualization available?
- Are there extra QC warnings or evidence details?

The database should not store full read-level data such as every read, CIGAR string, coverage array, or contig sequence in the core repeat tables. Those details should remain in external evidence files or be stored as summarized annotations if needed.

## Why This Is Better Than Using genomic_segments

The current database model is strong for CNV/SV findings, where the main finding is an interval:

```text
chromosome
start_pos
stop_pos
event_type
copy_number
```

Repeat expansions are different. The clinically important fields are:

```text
repeat_locus
repeat_motif
allele repeat counts
max repeat count
expansion category
data/evidence mode
```

Trying to force repeat expansions into `genomic_segments` would make repeat-specific searching and visualization awkward. A separate repeat expansion table makes repeat expansions first-class findings while still allowing shared classification, sign-out, notes, and cohort search.

## Relationship to Shared Interpretation Tables

Repeat expansion findings should eventually connect to the same interpretation layer used by SNV/indel and CNV/SV findings:

```text
repeat_expansions
  -> interpreted_calls
  -> variant_classifications
  -> signed_out_calls
```

This keeps the actual repeat call separate from clinical review.

The repeat table stores the call:

```text
allele counts
expansion category
quality status
raw evidence availability
```

The classification/sign-out tables store interpretation:

```text
classification label
review status
evidence score
evidence summary
clinical significance
reportability status
sign-out status
report text
```

This is useful because interpretations may change over time, while the original called result should remain traceable.

## Relationship to Cohort and Patient Search

The repeat expansion tables support repeat-specific details, but fast cohort search should probably use a shared search table or view such as `finding_index`.

For example:

```text
finding_index
  finding_type
  finding_id
  individual_id
  sample_accession_id
  gene_symbol
  chromosome
  classification_label
  clinical_significance
  review_status
  test_type
  collection_date
  diagnosis
  phenotype_terms
```

The repeat expansion source data remains in `repeat_expansions`, but the search layer can flatten commonly searched fields from:

- repeat_loci
- repeat_expansions
- sample/test/result tables
- variant_classifications
- signed_out_calls
- phenotype/case tables

This keeps the source schema clean while making patient and cohort search fast.

## Remaining Questions

### 1. Should the database store paths or links to supporting evidence files?

Question to ask:

```text
How should the database represent supporting evidence for repeat expansion results?
I understand we are not storing raw read data directly in the database, but I want to clarify whether the schema should track evidence file locations, such as BAM/CRAM paths, index files, repeat VCFs, TRGT plots, IGV sessions, or QC summaries, or whether evidence availability should be represented only as metadata on the interpreted repeat result.
```

Recommendation:

For the first implementation, store simple flags in `repeat_expansions`:

```text
raw_data_available
repeat_visualization_available
```

Reason:

This supports fast filtering and UI display without requiring a full file-link management system. If users later need to open BAM/CRAM, IGV sessions, TRGT plots, or QC reports directly from the application, add a separate `evidence_links` or `evidence_files` table.

### 2. Should allele counts stay as allele1/allele2 columns or move to an observed groups table?

Recommendation:

Keep this simple for now:

```text
allele1_repeat_count
allele2_repeat_count
max_repeat_count
```

Reason:

This is easy to query and supports the main clinical repeat count use case.

Possible future addition:

```text
repeat_expansion_observed_groups
```

This would be useful if the data needs to represent:

- more than two repeat-size groups
- mosaicism
- uncertain allele assignment
- haplotype 1 / haplotype 2
- maternal / paternal origin
- allele-specific read support
- assembly contig-specific calls

### 3. Should `tool_or_source` be stored in repeat_expansions?

Recommendation:

No. Do not store `tool_or_source` in `repeat_expansions`.

Reason:

The current schema already stores this information through:

```text
sample_test_results.calling_method
sample_test_results.pipeline_id
pipelines.software_name
pipelines.software_version
```

Adding `tool_or_source` directly to `repeat_expansions` would duplicate information.

### 4. Should confidence/evidence/review fields be stored in repeat_expansions?

Recommendation:

Store call-level technical quality in `repeat_expansions`, but store clinical interpretation evidence in `variant_classifications`.

Use:

```text
repeat_expansions.quality_status
repeat_expansions.repeat_count_confidence_range
```

for technical/caller uncertainty.

Use:

```text
variant_classifications.evidence_score
variant_classifications.evidence_summary
variant_classifications.review_status
```

for clinical interpretation/review.

Reason:

Technical confidence and clinical evidence are different concepts. A tool can be confident that a repeat count exists, but clinical interpretation may still be uncertain.

### 5. Should every field from the repeat expansion visualization spec become a database column?

Recommendation:

No.

Reason:

The visualization spec includes many detailed fields for raw reads, read support, coverage, assembly contigs, and QC. These are important for visualization and review, but they should not all become columns in `repeat_expansions`.

Use this rule:

```text
common clinician search field -> real column
tool-specific/evidence-detail field -> repeat_expansion_annotations
raw read/large sequence data -> external file, not stored directly in DB
```

## Final Recommendation

Use the three repeat-specific tables:

```text
repeat_loci
repeat_expansions
repeat_expansion_annotations
```

Keep the core repeat result small and queryable. Put detailed tool-specific fields in annotations. Keep clinical interpretation in shared classification/sign-out tables. Do not store raw read data inside the database. Use simple evidence availability flags for now, and only add evidence file/link tables if the UI needs to open supporting files directly.

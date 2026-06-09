# CNV Database GUI Prototype

This folder contains a first-pass Java Swing GUI for the CNV database project. It is separate from the CLI prototype so the existing import/report workflow can stay stable while the GUI evolves.

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
- Imports CNV TSV files through a strict all-or-nothing GUI workflow.
- Logs successful and failed GUI import attempts in `import_history`.
- Runs read-only SQL queries only.
- Rejects write SQL such as `INSERT`, `UPDATE`, `DELETE`, `DROP`, or `ALTER`.
- Provides query presets from the **Query Presets** menu.
- Shows query results in a `JTable`.
- Exports the currently displayed table to TSV without rerunning the query.

## GUI Import Rule

The GUI import pathway is intentionally stricter than the CLI prototype:

```text
If any row fails validation, no CNV rows from that file are inserted.
```

Failed attempts are still recorded in:

```text
import_history
```

The current required fields are:

```text
sample_accession_id
chromosome
start_pos
stop_pos
event_type
copy_number
genome_build
```

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

When multiple rows share the same `sample_accession_id` and `event_group_id`, the GUI attaches them to one `genomic_events` row. If two or more grouped rows are `TRANS`/`T`, the GUI creates `genomic_links` rows with `link_type = TRANSLOCATION`. Common aliases such as `group_id`, `variant_id`, `pair_id`, `link_id`, and `breakend_id` are accepted.

Each imported row still receives its own unique `event_id`. The repeated `event_group_id` is only the optional grouping label.

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
3. Import:

```text
gui/sample-data/valid_cnv.tsv
```

4. Try preset queries:

```text
Show all CNV calls
Count CNV calls by source file
CNV calls overlapping interval
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

```sql
SELECT
    gl.link_id,
    gl.event_id,
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

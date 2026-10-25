# Implementation Notes — Benchmark 08: ETL batch

## What a job does

`POST /api/v1/etl/run` accepts a job and runs it on a virtual thread:

1. Generates 50,000 records deterministically from the job id, with a fixed-seed generator.
2. Writes them to a temporary CSV (`id,category,value,timestamp`, values with six decimals).
3. Reads the CSV back. If it reads fewer records than it wrote, the job fails with
   `record_count_mismatch`.
4. Keeps values above 0.5 and aggregates by category (count, sum, average), in sorted category
   order. This produces the canonical output CSV.
5. Writes the output and computes its SHA-256.
6. Computes a **reference** from the in-memory records, with the same filter, the same aggregation
   and the same six-decimal rounding the CSV applies, and computes its SHA-256. The reference never
   reads a file.
7. If the two digests differ, the job fails with `digest_mismatch`. Both digests are kept on the
   job either way, so a failure can be inspected.
8. Deletes its temporary files on every path.

A round trip through the file, a parser that drops a line, or an aggregation that differs from the
reference will therefore fail the job, not pass silently.

## Status values

`GET /api/v1/etl/{id}/status` and the entries of `GET /api/v1/etl/jobs` return null for every value a
job has not produced: `records_processed`, `records_per_second`, `io_throughput_mb_s`,
`elapsed_ms`, `output_sha256`, `reference_sha256` and `digest_match`. A job that is still running
therefore never reports zero throughput. `failure` carries the reason for a failed job.

`GET /api/v1/etl/jobs` returns `{"jobs":[...]}`, an object, so the shared smoke client can parse it.

## Tests

- `./mvnw -B test` in `app` starts the app, runs a job, and checks: unfinished values are null; the job
  completes; 50,000 records; `digest_match` is true; the two digests are equal and are SHA-256 length;
  throughput and elapsed time are positive; the job is listed with its digest match.
- The mutation check was done in a scratch copy. The pipeline dropped its last record before
  aggregating: the job failed with `digest_mismatch`, the two digests differed, and the app test
  failed.
- `./mvnw -B test` in `harness` runs the shared functional client (run, list, health).

## Validity

This lane is **Tier 1 and not a benchmark**:

- Data scale and storage are fixed in code; a benchmark would need them controlled by the controller.
- The harness submits jobs and measures no load. Jobs run asynchronously, so a completion-rate measure
  needs the controller to wait for jobs.
- There is no Tier 2 telemetry for this workload yet.

## Limits

- Output is a summary by category, not a full data copy, so the file I/O cost is the input CSV.
- `elapsed_ms` includes record generation, so `io_throughput_mb_s` is an end-to-end figure over the whole
  job, not a pure I/O rate.
- The `value > 0.5` filter and the six-decimal rounding are deliberate parts of the canonical
  output. Changing either changes the reference too.

## Job lane

`benchctl lane jobs` submits jobs (sequentially or with a concurrency), polls each until it completes, and takes the application's own throughput, I/O and elapsed figures. Failed and timed-out jobs are listed in the envelope, and digest matches are counted. Resident memory is sampled from `/proc` for the application process.

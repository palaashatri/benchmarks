# Implementation Notes — Benchmark 07: Cold-start suite

## What it measures

Process-to-health time of child JVMs under three class-sharing modes:

| Mode | Child JVM option | Meaning |
|---|---|---|
| `default` | `-Xshare:auto` | the JDK's own class-data archive |
| `no-cds` | `-Xshare:off` | class sharing disabled |
| `appcds` | `-XX:SharedArchiveFile=<archive> -Xshare:on` | a dynamic AppCDS archive of this application's classes |

The clock starts when the parent spawns the child and stops when the child's `/runtime` reports the
token, role and pid the parent issued. The child's JVM options are read back from its own `/runtime`
and returned with each measurement, so the flags in a result come from the launched process.

`-Xshare:on` is used for `appcds` deliberately: an archive the child cannot use fails the child
(HTTP 500) instead of silently falling back to a plain start.

## How it is built

- The application runs from `build/run-sh/app.jar`. The JVM only archives classes from JARs for
  AppCDS, and children inherit the parent's classpath. A directory classpath fails with
  "non-empty directory".
- At startup, the top-level application (no `BENCH_SERVICE_ROLE`) does three things before it
  listens:
  1. probes the default archive with `java -Xshare:on -version`;
  2. starts a training child with `-XX:ArchiveClassesAtExit`, waits for readiness, then stops it with
     SIGTERM and a 60-second grace so the archive is written;
  3. records the archive's build time and size. Each mode's availability is recorded, not assumed.
- Children are started with the shared `ChildJvm` (`benchmarks/common`), which verifies pid, token and
  role on readiness and reaps the child. This workload added two overloads to it: JVM options, and
  `stop(grace)` for children that write state on exit.
- Measurements are serialised: one child at a time, so children do not compete for CPU with each
  other.
- Child processes are started with `Xms16m Xmx128m` for all modes, so the heap is the same in each.
- The AppCDS archive lives in a temporary directory removed by a shutdown hook. Not left behind on
  SIGTERM; a SIGKILL cannot run hooks.

## Routes

- `POST /api/v1/coldstart/measure` with `{"mode": "default|no-cds|appcds"}` (an empty body means
  `default`; any other mode is `400`). Returns the time, the child's pid, and its `/runtime` identity
  with JVM arguments.
- `GET /api/v1/coldstart/modes` — each mode's JVM options, probed availability and reason, and the
  AppCDS archive size and build time.
- `GET /api/v1/coldstart/samples` — the times measured per mode, in the order taken, with failure
  counts. The harness does not compute these; the application keeps them.
- `GET /runtime`, `GET /health`, `GET /metrics`, `GET /api/v1/jfr/stats`.

## Tests

- `app/run.sh test` starts the app from its JAR and checks: all three modes are available; each mode
  is measured; each child reports the expected `-Xshare` flag; the AppCDS child reports its
  `-XX:SharedArchiveFile`; an unknown mode is rejected; and one sample per mode is recorded.
- `harness/run.sh test` runs the shared functional client against the app. Any failed request fails
  the gate.

## Validity

This lane is **Tier 1 and not a benchmark**. It exercises the three modes, but the measurements do
not yet meet the validity gates:

- no host preflight: the governor is not observable in the sandbox, and the parent runs on an
  uncontrolled host;
- no page-cache control: a cold page cache needs root, so each start is warm in the page cache;
- no repetition statistics: a startup benchmark needs many starts per mode, with dispersion and
  outlier reporting. The controller has no lane that starts and stops the application per sample.

A sandbox check during implementation found that AppCDS children started faster than
default-archive children. Those numbers were not recorded as results.

## Limits

- The command-line `invoke` functions (`json-transform`, `thumbnail-stub`, `crud-tiny`,
  `lightweight-infer`) are synthetic and are not part of any measurement.
- `ChildJvm` polls readiness every 20 ms, so each time has that granularity.
- Parent-process figures (`/api/v1/jfr/stats`) describe the application, not the children. They are
  never mixed into a child measurement.
- A SIGKILLed application leaves its child temporary directories behind. Children exit when their
  parent's stdin closes, but the parent cannot remove files after SIGKILL. The shared
  `tools/workload_tests/test_architecture.py` stops applications with `kill()`, and its forced-exit
  test does so on purpose, so `bench-child-*` directories accumulate in the system temporary
  directory after those tests. This is a known gap in the shared test helper, not in this workload.

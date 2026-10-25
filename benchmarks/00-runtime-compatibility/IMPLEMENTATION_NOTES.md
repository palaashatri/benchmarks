# Implementation Notes - Benchmark 00: Runtime compatibility

## What it does

A Java 8-bytecode workload. Each `POST /work` seeds a pseudo-random sequence, sorts it, round-trips it
through deflate and inflate, and hashes it with SHA-256. The response carries the checksum, so a runtime
that computes differently is detected. The app is compiled with `--release 8` and runs unchanged on JDK
8 to 25.

## Measurement

- Tier 2 through the shared engine (`tier2.json`, `app/contract/load-mix.tsv`). The mix is one request
  template with a seed and a size drawn from the allowed range.
- The application exposes `/runtime` with the run token, pid, Java version, input arguments and
  collectors, so the engine checks the launched process before load.
- The application publishes its bound port through `BENCH_PORT_FILE` for the shared test owners.
- `app/src/main/java/com/palaashatri/bench/b00/app/BenchmarkApp.java` is the standard entry point. It
  delegates to the Java 8 application class, which the controller's artifact lane also uses.

## Limits

- Compute only: no GC or allocation comparison beyond what the JVM reports, and no network.
- Every runtime is measured separately; the compatibility question is whether the numbers and checksums
  agree, not whether one runtime is faster than another.
- The controller's artifact lane still builds the Java 8 JAR through `./run.sh artifact`; that path is
  unchanged.

# CLI contract for ETL Batch Job

Entrypoint: `BenchmarkApp` via `./gradlew run --args="..."` or Maven exec.

Supported benchmark operations:
- `--in PATH --out PATH --impl single-jvm`
- `--impl spark`
- `--offheap true`

All deterministic generators accept `--seed`; output JSON is written under `--out`.

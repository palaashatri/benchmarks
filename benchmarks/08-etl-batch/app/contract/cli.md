# CLI contract for ETL Batch Job

Entrypoint: `BenchmarkApp` via the Maven exec goal.

Supported benchmark operations:
- `--in PATH --out PATH --impl single-jvm`
- `--impl spark`
- `--offheap true`

All deterministic generators accept `--seed`; output JSON is written under `--out`.

# CLI contract for Coldstart Function Suite

Entrypoint: `BenchmarkApp` via `./gradlew run --args="..."` or Maven exec.

Supported benchmark operations:
- `json-transform`
- `thumbnail-stub`
- `crud-tiny`
- `lightweight-infer`

All deterministic generators accept `--seed`; output JSON is written under `--out`.

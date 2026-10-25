package com.palaashatri.bench.ctl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Structural checks on a result document, whether one normalized result or a run-level aggregate. */
public final class ResultEnvelopes {
    private static final List<String> TIERS = List.of("tier-0", "tier-1", "tier-2", "tier-3");
    private static final List<String> RUN_KINDS = List.of("smoke", "calibration", "benchmark");

    private ResultEnvelopes() {
    }

    public static List<String> validate(Map<String, Object> data) {
        List<String> errors = new ArrayList<>();
        if (data.containsKey("results")) {
            for (String key : List.of("schema_version", "run_kind", "measurement_valid", "results")) {
                if (!data.containsKey(key)) {
                    errors.add("missing required aggregate field: " + key);
                }
            }
            if (!(data.get("results") instanceof List<?> children)) {
                errors.add("aggregate results must be an array");
                return errors;
            }
            for (int index = 0; index < children.size(); index++) {
                for (String error : validate(Json.asMap(children.get(index)))) {
                    errors.add("results[" + index + "]: " + error);
                }
            }
            boolean childInvalid = children.stream().anyMatch(child -> !Boolean.TRUE.equals(Json.asMap(child).get("measurement_valid")));
            if (Boolean.TRUE.equals(data.get("measurement_valid")) && childInvalid) {
                errors.add("aggregate cannot be valid when a child result is invalid");
            }
            return errors;
        }
        for (String key : List.of("schema_version", "run_kind", "implementation_tier", "measurement_valid",
                "invalid_reasons", "warnings")) {
            if (!data.containsKey(key)) {
                errors.add("missing required field: " + key);
            }
        }
        if (!RUN_KINDS.contains(data.get("run_kind"))) {
            errors.add("invalid run_kind");
        }
        if (!TIERS.contains(data.get("implementation_tier"))) {
            errors.add("invalid implementation_tier");
        }
        if (Boolean.TRUE.equals(data.get("measurement_valid")) && !Json.asList(data.get("invalid_reasons")).isEmpty()) {
            errors.add("measurement_valid cannot be true when invalid_reasons is non-empty");
        }
        return errors;
    }
}

package com.palaashatri.bench.ctl;

import java.util.LinkedHashMap;

import java.util.Map;

/** Statistical comparison of two measurement-valid benchmark results. Anything less is reported as invalid. */
public final class Compare {
    private Compare() {
    }

    public static Map<String, Object> compare(Map<String, Object> left, Map<String, Object> right) {
        if (!Boolean.TRUE.equals(left.get("measurement_valid")) || !Boolean.TRUE.equals(right.get("measurement_valid"))) {
            return invalid("both results must be measurement_valid");
        }
        if (!String.valueOf(left.get("benchmark")).equals(String.valueOf(right.get("benchmark")))) {
            return invalid("results describe different workloads");
        }
        if (!"benchmark".equals(left.get("run_kind")) || !"benchmark".equals(right.get("run_kind"))) {
            return invalid("only benchmark results may be compared");
        }
        Map<String, Object> leftMetric = metric(left, "throughput");
        Map<String, Object> rightMetric = metric(right, "throughput");
        Double leftThroughput = Json.number(leftMetric.get("median"));
        Double rightThroughput = Json.number(rightMetric.get("median"));
        if (leftThroughput == null || rightThroughput == null) {
            return invalid("both results require numeric throughput");
        }
        Double delta = leftThroughput != 0 ? (rightThroughput - leftThroughput) / leftThroughput * 100 : null;
        Map<String, Object> leftCi = Json.asMap(leftMetric.get("bootstrap_mean_ci"));
        Map<String, Object> rightCi = Json.asMap(rightMetric.get("bootstrap_mean_ci"));
        Double leftLow = Json.number(leftCi.get("low"));
        Double leftHigh = Json.number(leftCi.get("high"));
        Double rightLow = Json.number(rightCi.get("low"));
        Double rightHigh = Json.number(rightCi.get("high"));
        boolean overlapping = leftLow != null && leftHigh != null && rightLow != null && rightHigh != null
                && !(leftHigh < rightLow || rightHigh < leftLow);
        String outcome;
        if (delta == null || overlapping || Math.abs(delta) < 2) {
            outcome = "inconclusive";
        } else {
            outcome = delta > 0 ? "improvement" : "regression";
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("outcome", outcome);
        document.put("throughput_delta_pct", delta);
        document.put("left_throughput_median", leftThroughput);
        document.put("right_throughput_median", rightThroughput);
        document.put("confidence_intervals_overlap", overlapping);
        return document;
    }

    /** The aggregate metric, or a single-value summary built from a key performance indicator. */
    static Map<String, Object> metric(Map<String, Object> result, String name) {
        Object aggregate = Json.asMap(result.get("aggregate")).get(name);
        if (aggregate instanceof Map<?, ?>) {
            return Json.asMap(aggregate);
        }
        Double value = Json.number(Json.asMap(result.get("kpis")).get(name));
        if (value == null) {
            return Map.of();
        }
        return Json.map("median", value, "mean", value, "bootstrap_mean_ci", Map.of());
    }

    private static Map<String, Object> invalid(String reason) {
        return Json.map("outcome", "invalid", "reason", reason);
    }

}

package com.palaashatri.bench.ctl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Statistics shared by the Tier 2 engine, the comparison and the report. Every method returns null (or an empty
 * result) when the data cannot support the statistic. Nothing is replaced by zero.
 */
public final class Stats {
    /** Iglewicz and Hoaglin's recommended cut-off for the modified z-score. */
    static final double MODIFIED_Z_THRESHOLD = 3.5;
    /** Consistency constant that makes the MAD comparable to a standard deviation. */
    static final double MAD_SCALE = 0.6745;
    /** Warm-up detector: throughput is stable when STABLE_WINDOW consecutive one-second buckets vary by at most STABLE_MAX_CV. */
    static final int STABLE_WINDOW = 5;
    static final double STABLE_MAX_CV = 0.05;

    private Stats() {
    }

    public static Double mean(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        double total = 0;
        for (double value : values) {
            total += value;
        }
        return total / values.size();
    }

    /** Sample standard deviation (n - 1 denominator), or null for fewer than two values. */
    public static Double standardDeviation(List<Double> values) {
        if (values.size() < 2) {
            return null;
        }
        double mean = mean(values);
        double squares = 0;
        for (double value : values) {
            squares += (value - mean) * (value - mean);
        }
        return Math.sqrt(squares / (values.size() - 1));
    }

    public static Double median(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    public static Double medianAbsoluteDeviation(List<Double> values) {
        Double center = median(values);
        if (center == null) {
            return null;
        }
        List<Double> deviations = new ArrayList<>();
        for (double value : values) {
            deviations.add(Math.abs(value - center));
        }
        return median(deviations);
    }

    /** Standard deviation over the mean, or null when the mean is zero or the spread is undefined. */
    public static Double coefficientOfVariation(List<Double> values) {
        Double deviation = standardDeviation(values);
        Double mean = mean(values);
        if (deviation == null || mean == null || mean == 0) {
            return null;
        }
        return deviation / mean;
    }

    /** Percentile bootstrap confidence interval of the mean at 95 %, seeded so that runs are repeatable. */
    public static Map<String, Object> bootstrapInterval(List<Double> values, long seed) {
        if (values.size() < 2) {
            return Json.map("low", null, "high", null, "confidence", 0.95);
        }
        Random generator = new Random(seed);
        int samples = 10_000;
        double[] means = new double[samples];
        for (int draw = 0; draw < samples; draw++) {
            double total = 0;
            for (int index = 0; index < values.size(); index++) {
                total += values.get(generator.nextInt(values.size()));
            }
            means[draw] = total / values.size();
        }
        java.util.Arrays.sort(means);
        return Json.map(
                "low", means[(int) (.025 * (samples - 1))],
                "high", means[(int) (.975 * (samples - 1))],
                "confidence", 0.95);
    }

    /** Points whose modified z-score exceeds the threshold. Empty when the MAD is zero: nothing can be separated. */
    public static List<Map<String, Object>> modifiedZOutliers(List<Double> values) {
        Double mad = medianAbsoluteDeviation(values);
        List<Map<String, Object>> flagged = new ArrayList<>();
        if (values.isEmpty() || mad == null || mad == 0) {
            return flagged;
        }
        double center = median(values);
        for (int index = 0; index < values.size(); index++) {
            double score = MAD_SCALE * (values.get(index) - center) / mad;
            if (Math.abs(score) > MODIFIED_Z_THRESHOLD) {
                flagged.add(Json.map("index", index, "value", values.get(index), "modified_z", score,
                        "method", "modified_z"));
            }
        }
        return flagged;
    }

    /** Points outside the Tukey fences, with quartiles from the inclusive method. Needs at least four values. */
    public static List<Map<String, Object>> iqrOutliers(List<Double> values) {
        List<Map<String, Object>> flagged = new ArrayList<>();
        if (values.size() < 4) {
            return flagged;
        }
        double lower = inclusivePercentile(values, 25);
        double upper = inclusivePercentile(values, 75);
        double spread = upper - lower;
        double lowFence = lower - 1.5 * spread;
        double highFence = upper + 1.5 * spread;
        for (int index = 0; index < values.size(); index++) {
            double value = values.get(index);
            if (value < lowFence || value > highFence) {
                flagged.add(Json.map("index", index, "value", value, "method", "iqr"));
            }
        }
        return flagged;
    }

    /** Explicit outlier report for one metric. Outliers are reported with their repetition numbers, never removed. */
    public static Map<String, Object> describeOutliers(String name, List<Double> values, List<Long> repetitions) {
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Map<String, Object> finding : modifiedZOutliers(values)) {
            findings.add(outlierFinding(finding, repetitions));
        }
        for (Map<String, Object> finding : iqrOutliers(values)) {
            findings.add(outlierFinding(finding, repetitions));
        }
        return Json.map("metric", name, "count", values.size(), "outliers", findings);
    }

    private static Map<String, Object> outlierFinding(Map<String, Object> finding, List<Long> repetitions) {
        int index = (Integer) finding.get("index");
        Map<String, Object> out = Json.map("repetition", repetitions.get(index), "value", finding.get("value"),
                "method", finding.get("method"));
        if (finding.containsKey("modified_z")) {
            out.put("modified_z", finding.get("modified_z"));
        }
        return out;
    }

    /** Index of the first bucket that starts a stable run, or null. Zero or missing buckets are never stable. */
    public static Integer findStableWindow(List<Double> series, int window, double maxCv) {
        if (window < 2 || series.size() < window) {
            return null;
        }
        for (int start = 0; start <= series.size() - window; start++) {
            List<Double> chunk = series.subList(start, start + window);
            if (chunk.stream().anyMatch(value -> value == null || value <= 0)) {
                continue;
            }
            Double cv = coefficientOfVariation(chunk);
            if (cv != null && cv <= maxCv) {
                return start;
            }
        }
        return null;
    }

    /**
     * Whether throughput settled before the measurement phase began. The stable window must start inside the
     * warm-up phase: a window that begins only after measurement started cannot show that warm-up was enough.
     */
    public static Map<String, Object> warmupStability(List<Double> series, int warmupSeconds, int window, double maxCv) {
        Integer start = findStableWindow(series, window, maxCv);
        boolean stable = start != null && start + window <= warmupSeconds;
        return Json.map(
                "method", "consecutive_window_cv",
                "window_seconds", window,
                "max_cv", maxCv,
                "stable_at_second", start,
                "stable_before_measurement", stable,
                "warmup_seconds", warmupSeconds,
                "bucket_count", series.size());
    }

    public static Map<String, Object> warmupStability(List<Double> series, int warmupSeconds) {
        return warmupStability(series, warmupSeconds, STABLE_WINDOW, STABLE_MAX_CV);
    }

    /** Linear interpolation between closest ranks, matching the inclusive method of Python's statistics module. */
    static double inclusivePercentile(List<Double> values, double percentile) {
        List<Double> sorted = values.stream().sorted().toList();
        double position = (sorted.size() - 1) * percentile / 100.0;
        int low = (int) Math.floor(position);
        int high = Math.min(low + 1, sorted.size() - 1);
        double fraction = position - low;
        return sorted.get(low) + (sorted.get(high) - sorted.get(low)) * fraction;
    }
}

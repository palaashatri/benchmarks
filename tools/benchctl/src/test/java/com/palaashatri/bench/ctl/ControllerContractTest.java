package com.palaashatri.bench.ctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Controller contracts: version parsing, manifest validation, result envelope validation, and manifest loading. */
class ControllerContractTest {
    @TempDir
    Path temporary;

    @Test
    void parsesJava8AndModernVersionStrings() {
        assertEquals(8L, Runtimes.parseFeatureVersion("java version \"1.8.0_402\""));
        assertEquals(25L, Runtimes.parseFeatureVersion("openjdk version \"25-ea\""));
    }

    @Test
    void benchmarkRunsRequireAtLeastFiveRepetitions() {
        List<String> errors = Experiments.validate(Map.of(
                "schema_version", "1.0.0",
                "run_kind", "benchmark",
                "workloads", List.of("x"),
                "runtimes", List.of(21),
                "gcs", List.of("g1"),
                "repetitions", 1));
        assertTrue(errors.contains("benchmark runs require at least 5 repetitions"), errors.toString());
    }

    @Test
    void invalidResultCannotBeMarkedValid() {
        List<String> errors = ResultEnvelopes.validate(Map.of(
                "schema_version", "1.0.0",
                "run_kind", "benchmark",
                "implementation_tier", "tier-2",
                "measurement_valid", true,
                "invalid_reasons", List.of("bad environment"),
                "warnings", List.of()));
        assertTrue(errors.stream().anyMatch(error -> error.contains("measurement_valid")), errors.toString());
    }

    @Test
    void aggregateEnvelopeWithAnInvalidChildIsAcceptedWhenNotMarkedValid() {
        Map<String, Object> child = Map.of(
                "schema_version", "1.0.0",
                "run_kind", "smoke",
                "implementation_tier", "tier-1",
                "measurement_valid", false,
                "invalid_reasons", List.of("smoke"),
                "warnings", List.of());
        Map<String, Object> aggregate = Map.of(
                "schema_version", "1.0.0",
                "run_kind", "smoke",
                "measurement_valid", false,
                "results", List.of(child));
        assertEquals(List.of(), ResultEnvelopes.validate(aggregate));
    }

    @Test
    void jsonIsAValidYamlSubsetForExperimentFiles() throws Exception {
        Path experiment = temporary.resolve("experiment.yaml");
        Files.writeString(experiment, Json.write(Map.of("schema_version", "1.0.0")));
        assertEquals("1.0.0", Experiments.load(experiment).get("schema_version"));
    }
}

package com.palaashatri.bench.ctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every workload that declares a Tier 2 contract is catalogued as Tier 2, and its contract is well formed. */
class Tier2ContractTest {
    private static final Set<String> REQUIRED_KEYS = Set.of("app_dir", "main_class", "token_prefix", "identity_path",
            "correctness", "build", "classpath_file");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE");

    private final Repo repo = Repo.locate();

    private List<String> declaredWorkloads() throws IOException {
        try (Stream<Path> directories = Files.list(repo.root().resolve("benchmarks"))) {
            return directories.filter(directory -> Files.isRegularFile(directory.resolve("tier2.json")))
                    .map(directory -> directory.getFileName().toString()).sorted().toList();
        }
    }

    private Map<String, Object> contract(String workload) {
        try {
            return Json.asMap(Json.parse(Files.readString(repo.workloadDirectory(workload).resolve("tier2.json"))));
        } catch (IOException exception) {
            throw new BenchError("cannot read the Tier 2 contract for " + workload, exception);
        }
    }

    /** The load shape a contract declares; http-mix when it declares none, as the engine reads it. */
    private static String loadShape(Map<String, Object> spec) {
        return spec.containsKey("load_shape") ? String.valueOf(spec.get("load_shape")) : "http-mix";
    }

    /** The keys a contract needs: the common ones, plus the one its load shape reads. */
    private static Set<String> requiredKeys(Map<String, Object> spec) {
        Set<String> required = new HashSet<>(REQUIRED_KEYS);
        required.add(switch (loadShape(spec)) {
            case "http-mix" -> "load_mix";
            case "jobs" -> "jobs";
            case "startup" -> "startup";
            case "framed-tcp" -> "driver";
            default -> throw new BenchError("unknown load_shape " + loadShape(spec));
        });
        return required;
    }

    @Test
    void everyDeclaredContractHasTheRequiredFields() throws IOException {
        for (String workload : declaredWorkloads()) {
            Map<String, Object> spec = contract(workload);
            Set<String> missing = requiredKeys(spec);
            missing.removeAll(spec.keySet());
            assertTrue(missing.isEmpty(), workload + " is missing " + missing);
        }
    }

    @Test
    void everyContractNamesACataloguedTier2Workload() throws IOException {
        Map<String, Object> tiers = new java.util.HashMap<>();
        for (Map<String, Object> workload : repo.catalog()) {
            tiers.put(String.valueOf(workload.get("id")), workload.get("tier"));
        }
        for (String workload : declaredWorkloads()) {
            assertTrue(tiers.containsKey(workload), workload);
            assertEquals("tier-2", tiers.get(workload), workload + " has a contract but is not Tier 2");
        }
    }

    @Test
    void contractPathsExist() throws IOException {
        for (String workload : declaredWorkloads()) {
            Path directory = repo.workloadDirectory(workload);
            Map<String, Object> spec = contract(workload);
            assertTrue(Files.isDirectory(directory.resolve(String.valueOf(spec.get("app_dir")))), workload);
            if ("http-mix".equals(loadShape(spec))) {
                assertTrue(Files.isRegularFile(directory.resolve(String.valueOf(spec.get("load_mix")))), workload);
            }
            if (spec.containsKey("driver")) {
                Map<String, Object> driver = Json.asMap(spec.get("driver"));
                assertTrue(Files.isDirectory(directory.resolve(String.valueOf(driver.get("directory")))), workload);
            }
        }
    }

    @Test
    void requestMixesAreWellFormed() throws IOException {
        for (String workload : declaredWorkloads()) {
            Map<String, Object> spec = contract(workload);
            if (!"http-mix".equals(loadShape(spec))) {
                continue;
            }
            Path mix = repo.workloadDirectory(workload).resolve(String.valueOf(spec.get("load_mix")));
            List<String> lines = Files.readAllLines(mix).stream()
                    .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
            assertFalse(lines.isEmpty(), workload + " has no request templates");
            for (String line : lines) {
                String[] columns = line.split("\t", -1);
                assertTrue(columns.length >= 3, workload + ": " + line);
                assertTrue(columns[0].matches("[0-9]+") && Long.parseLong(columns[0]) > 0, workload + ": weight in " + line);
                assertTrue(METHODS.contains(columns[1]), workload + ": " + line);
                assertTrue(columns[2].startsWith("/"), workload + ": path in " + line);
            }
        }
    }

    @Test
    void loadingAWorkloadReturnsItsJdkBand() {
        Tier2.Workload workload = Tier2.load(repo, "12-hft-trading-gateway");
        assertEquals(21, workload.minJdk());
        assertEquals(25, workload.maxJdk());
        assertEquals("tier-2", workload.tier());
        assertEquals("hft", workload.tokenPrefix());
    }

    @Test
    void unifiedLogsNameTheCollectorFromTheUsingLine() {
        assertEquals("g1", Tier2.actualCollector("[0.01s][info][gc,init] Using G1\n"));
        assertEquals("zgc", Tier2.actualCollector("[0.01s][info][gc,init] Using Z\n"));
    }

    @Test
    void legacyJdk8LogsNameTheCollectorFromTheirPauseLabels() {
        assertEquals("g1", Tier2.actualCollector("4.971: [GC pause (G1 Evacuation Pause) (young), 0.0074204 secs]\n"));
        assertEquals("serial", Tier2.actualCollector("0.2: [GC [DefNew: 17K->2K(19K), 0.0003 secs]\n"));
        assertEquals("parallel", Tier2.actualCollector("0.2: [GC [PSYoungGen: 17K->2K(19K)] 1K\n"));
        assertEquals("cms", Tier2.actualCollector("0.2: [GC [ParNew: 17K->2K(19K), 0.0003 secs]\n"));
    }

    @Test
    void aLogWithoutACollectorLabelYieldsNullNotAGuess() {
        assertNull(Tier2.actualCollector("CommandLine flags: -XX:+UseG1GC\n"));
    }
}

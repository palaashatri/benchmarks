import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Generates the large-monolith component graph as Java source. Run with the JDK's source launcher, so it needs no
 * build step on any operating system:
 *
 * <pre>java tools/GenerateGraph.java --out DIR [--modules N] [--depth N]</pre>
 *
 * The graph has MODULES chains of DEPTH components. Inside a chain each component depends on the one before it
 * (its primary). From the second module on, each component also depends on the same row of the previous module
 * (its secondary). Dependencies only point backwards, so the graph is acyclic. The generated classes are ordinary
 * beans: the application's container instantiates them by reflection, dependencies first, from their constructor
 * parameters.
 *
 * <p>Arithmetic is 64-bit wrapping, which is the same bit pattern as the unsigned 64-bit model in the workload's
 * contract. Results are written as signed longs.
 */
public final class GenerateGraph {
    static final String PACKAGE = "com.palaashatri.bench.b13.app.graph";
    static final int DEFAULT_MODULES = 20;
    static final int DEFAULT_DEPTH = 50;
    /** Must match MonolithServer.INPUT and the arithmetic in Mix.java. */
    static final long INPUT = 0x5DEECE66DL;

    private GenerateGraph() {
    }

    public static void main(String[] args) throws IOException {
        Path out = null;
        int modules = DEFAULT_MODULES;
        int depth = DEFAULT_DEPTH;
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--out" -> out = Path.of(args[++index]);
                case "--modules" -> modules = Integer.parseInt(args[++index]);
                case "--depth" -> depth = Integer.parseInt(args[++index]);
                default -> throw new IllegalArgumentException("unknown argument: " + args[index]);
            }
        }
        if (out == null) {
            throw new IllegalArgumentException("--out is required");
        }
        if (modules < 1 || depth < 1) {
            throw new IllegalArgumentException("--modules and --depth must be at least 1");
        }

        Path packageDirectory = out;
        for (String part : PACKAGE.split("\\.")) {
            packageDirectory = packageDirectory.resolve(part);
        }
        Files.createDirectories(packageDirectory);
        try (Stream<Path> stale = Files.list(packageDirectory)) {
            for (Path path : stale.filter(path -> path.toString().endsWith(".java")).toList()) {
                Files.delete(path);
            }
        }
        for (int module = 0; module < modules; module++) {
            for (int row = 0; row < depth; row++) {
                write(packageDirectory.resolve(className(module, row) + ".java"), nodeSource(module, row));
            }
        }
        write(packageDirectory.resolve("Manifest.java"), manifestSource(modules, depth));
        System.out.println("generated " + (modules * depth) + " components into " + packageDirectory);
    }

    static long rotl(long value, int bits) {
        return (value << bits) | (value >>> (64 - bits));
    }

    static long derive(long tag, long first, long second) {
        long h = tag * 0x9E3779B97F4A7C15L;
        h ^= rotl(first, 21) * 0xC2B2AE3D27D4EB4FL;
        h ^= rotl(second, 42) * 0x165667B19E3779F9L;
        return h ^ (h >>> 29);
    }

    static long step(long value, long seed) {
        long x = (value ^ seed) * 0xFF51AFD7ED558CCDL;
        x ^= x >>> 33;
        return x + seed;
    }

    /** Independent model of the wiring and the traversal. */
    static long expectedResult(int modules, int depth) {
        Map<String, Long> seeds = new HashMap<>();
        for (int module = 0; module < modules; module++) {
            for (int row = 0; row < depth; row++) {
                long tag = (module + 1) * 1000L + row + 1;
                long first = row > 0 ? seeds.get(key(module, row - 1)) : 0L;
                long second = module > 0 ? seeds.get(key(module - 1, row)) : 0L;
                seeds.put(key(module, row), derive(tag, first, second));
            }
        }
        long value = INPUT;
        for (int module = 0; module < modules; module++) {
            for (int row = depth - 1; row >= 0; row--) {
                value = step(value, seeds.get(key(module, row)));
            }
        }
        return value;
    }

    static String key(int module, int row) {
        return module + ":" + row;
    }

    static String beanId(int module, int row) {
        return String.format("m%02d-r%02d", module, row);
    }

    static String className(int module, int row) {
        return String.format("Node_m%02d_r%02d", module, row);
    }

    static String nodeSource(int module, int row) {
        long tag = (module + 1) * 1000L + row + 1;
        List<String[]> dependencies = new ArrayList<>();
        if (row > 0) {
            dependencies.add(new String[] {"primary", beanId(module, row - 1)});
        }
        if (module > 0) {
            dependencies.add(new String[] {"secondary", beanId(module - 1, row)});
        }
        List<String> parameters = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        List<String> assignments = new ArrayList<>();
        for (String[] dependency : dependencies) {
            parameters.add("@Dep(\"" + dependency[1] + "\") Component " + dependency[0]);
            fields.add("    private final Component " + dependency[0] + ";");
            assignments.add("        this." + dependency[0] + " = " + dependency[0] + ";");
        }
        boolean hasPrimary = row > 0;
        boolean hasSecondary = module > 0;
        String primarySeed = hasPrimary ? "primary.seed()" : "0L";
        String secondarySeed = hasSecondary ? "secondary.seed()" : "0L";
        String applyBody = hasPrimary
                ? "        return primary.apply(Mix.step(value, seed));"
                : "        return Mix.step(value, seed);";
        String name = className(module, row);

        List<String> lines = new ArrayList<>(List.of(
                "package " + PACKAGE + ";",
                "",
                "import com.palaashatri.bench.b13.app.Bean;",
                "import com.palaashatri.bench.b13.app.Component;",
                "import com.palaashatri.bench.b13.app.Dep;",
                "import com.palaashatri.bench.b13.app.Mix;",
                "",
                "@Bean(\"" + beanId(module, row) + "\")",
                "public final class " + name + " implements Component {"));
        lines.addAll(fields);
        lines.add("    private final long seed;");
        lines.add("");
        lines.add("    public " + name + "(" + String.join(", ", parameters) + ") {");
        lines.addAll(assignments);
        lines.add("        this.seed = Mix.derive(" + tag + "L, " + primarySeed + ", " + secondarySeed + ");");
        lines.add("    }");
        lines.add("");
        lines.add("    @Override");
        lines.add("    public long seed() {");
        lines.add("        return seed;");
        lines.add("    }");
        lines.add("");
        lines.add("    @Override");
        lines.add("    public long apply(long value) {");
        lines.add(applyBody);
        lines.add("    }");
        lines.add("}");
        lines.add("");
        return String.join("\n", lines);
    }

    static String manifestSource(int modules, int depth) {
        List<String> names = new ArrayList<>();
        for (int module = 0; module < modules; module++) {
            for (int row = 0; row < depth; row++) {
                names.add("        \"" + PACKAGE + "." + className(module, row) + "\",");
            }
        }
        List<String> lines = new ArrayList<>(List.of(
                "package " + PACKAGE + ";",
                "",
                "/** Generated by tools/GenerateGraph.java. Do not edit. */",
                "public final class Manifest {",
                "    public static final int MODULES = " + modules + ";",
                "    public static final int DEPTH = " + depth + ";",
                "    public static final long EXPECTED_RESULT = " + expectedResult(modules, depth) + "L;",
                "    public static final String[] BEAN_CLASSES = {"));
        lines.addAll(names);
        lines.addAll(List.of("    };", "", "    private Manifest() { }", "}", ""));
        return String.join("\n", lines);
    }

    private static void write(Path path, String text) throws IOException {
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }
}

package com.palaashatri.bench.ctl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs an OpenJDK HotSpot build for a feature release under {@code .benchctl/jdks}, where discovery finds it.
 *
 * <p>The source is the Eclipse Adoptium API, which runs on Windows, macOS and Linux. Each archive is accepted only
 * when its SHA-256 matches the checksum the API publishes for it. Extraction uses the platform's own {@code tar} for
 * .tar.gz archives (present on Windows 10 and later, macOS and Linux) and the JDK for .zip archives.
 */
public final class JdkFetch {
    static final String DEFAULT_API = "https://api.adoptium.net/v3";
    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    private final String api;

    public JdkFetch(String api) {
        this.api = api.endsWith("/") ? api.substring(0, api.length() - 1) : api;
    }

    /** The API's name for the platform the controller runs on. */
    public static String operatingSystem() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) {
            return "windows";
        }
        if (name.startsWith("mac")) {
            return "mac";
        }
        return "linux";
    }

    /** The API's name for the processor architecture. */
    public static String architecture() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.equals("aarch64") || arch.equals("arm64") ? "aarch64" : "x64";
    }

    /** Installs the latest GA build of a feature release and returns its JDK home. */
    public Path install(int feature, Path jdks) {
        String os = operatingSystem();
        String architecture = architecture();
        String url = api + "/assets/latest/" + feature + "/hotspot?architecture=" + architecture
                + "&image_type=jdk&os=" + os + "&vendor=eclipse";
        Map<String, Object> asset = selectAsset(Json.asList(Json.parse(get(url))), feature);
        Map<String, Object> pkg = Json.asMap(Json.asMap(asset.get("binary")).get("package"));
        String link = String.valueOf(pkg.get("link"));
        String expected = String.valueOf(pkg.get("checksum"));
        String name = String.valueOf(pkg.get("name"));
        try {
            Files.createDirectories(jdks);
        } catch (IOException exception) {
            throw new BenchError("cannot create " + jdks + ": " + exception.getMessage(), exception);
        }
        Path staging;
        try {
            staging = Files.createTempDirectory(jdks, ".staging-");
        } catch (IOException exception) {
            throw new BenchError("cannot create a staging directory: " + exception.getMessage(), exception);
        }
        try {
            Path archive = staging.resolve(name);
            download(link, archive);
            String actual = Digest.sha256(archive);
            if (!actual.equalsIgnoreCase(expected)) {
                throw new BenchError("SHA-256 mismatch for " + name + ": expected " + expected + ", got " + actual);
            }
            extract(archive, staging);
            Path top = topLevelJdk(staging);
            Path destination = jdks.resolve(top.getFileName().toString());
            if (!Files.exists(destination)) {
                Files.move(top, destination);
            }
            return jdkHome(destination);
        } catch (IOException exception) {
            throw new BenchError("cannot install " + name + ": " + exception.getMessage(), exception);
        } finally {
            deleteRecursively(staging);
        }
    }

    static Map<String, Object> selectAsset(List<Object> assets, int feature) {
        for (Object item : assets) {
            Map<String, Object> asset = Json.asMap(item);
            Map<String, Object> version = Json.asMap(asset.get("version"));
            Map<String, Object> binary = Json.asMap(asset.get("binary"));
            if (!"jdk".equals(binary.get("image_type")) || !"hotspot".equals(binary.get("jvm_impl"))) {
                continue;
            }
            if (version.get("major") instanceof Number major && major.intValue() == feature) {
                return asset;
            }
        }
        throw new BenchError("no OpenJDK HotSpot " + feature + " build is published for " + operatingSystem()
                + " " + architecture());
    }

    private String get(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout((int) TIMEOUT.toMillis());
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new BenchError("GET " + url + " returned HTTP " + status);
            }
            try (InputStream in = connection.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            throw new BenchError("cannot reach " + url + ": " + exception.getMessage(), exception);
        }
    }

    private static void download(String link, Path destination) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(link).openConnection();
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout((int) TIMEOUT.toMillis());
            if (connection.getResponseCode() != 200) {
                throw new BenchError("download failed with HTTP " + connection.getResponseCode() + ": " + link);
            }
            try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(destination)) {
                in.transferTo(out);
            }
        } catch (IOException exception) {
            throw new BenchError("download failed: " + link + ": " + exception.getMessage(), exception);
        }
    }

    static void extract(Path archive, Path into) {
        String name = archive.getFileName().toString();
        if (name.endsWith(".zip")) {
            extractZip(archive, into);
            return;
        }
        Shell.Result result = Shell.run(List.of("tar", "-xzf", archive.toString(), "-C", into.toString()),
                into, null, Duration.ofMinutes(10));
        if (!result.succeeded()) {
            throw new BenchError("tar could not extract " + archive + ": " + result.combined());
        }
    }

    private static void extractZip(Path archive, Path into) {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            Path root = into.toAbsolutePath().normalize();
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new BenchError("archive entry escapes the install directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException exception) {
            throw new BenchError("cannot extract " + archive + ": " + exception.getMessage(), exception);
        }
    }

    /** The one directory an archive unpacks into that holds a JDK, either directly or as a macOS bundle. */
    static Path topLevelJdk(Path staging) {
        try (java.util.stream.Stream<Path> children = Files.list(staging)) {
            for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                if (Files.isDirectory(child.resolve("bin")) || Files.isDirectory(child.resolve("Contents").resolve("Home"))) {
                    return child;
                }
            }
        } catch (IOException exception) {
            throw new BenchError("cannot read " + staging + ": " + exception.getMessage(), exception);
        }
        throw new BenchError("the archive does not contain a JDK under " + staging);
    }

    /** The directory that holds the JDK's bin folder: the top-level directory, or its macOS bundle home. */
    static Path jdkHome(Path topLevel) {
        if (Files.isDirectory(topLevel.resolve("bin"))) {
            return topLevel.toAbsolutePath();
        }
        return topLevel.resolve("Contents").resolve("Home").toAbsolutePath();
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
            for (Path item : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        } catch (IOException ignored) {
            // A staging leftover does not affect discovery; the next install starts a new staging directory.
        }
    }
}

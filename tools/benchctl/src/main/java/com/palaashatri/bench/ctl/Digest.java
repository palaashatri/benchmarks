package com.palaashatri.bench.ctl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;

/** SHA-256 digests of files and trees, as lowercase hexadecimal. */
public final class Digest {
    private Digest() {
    }

    public static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] chunk = new byte[1024 * 1024];
            int count;
            while ((count = in.read(chunk)) != -1) {
                digest.update(chunk, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new BenchError("cannot read " + file + ": " + exception.getMessage(), exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", exception);
        }
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Every regular file under the root, excluding {@code checksums.json}, with its digest and size. */
    public static java.util.List<java.util.Map<String, Object>> tree(Path root) {
        java.util.List<java.util.Map<String, Object>> values = new java.util.ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                if (path.getFileName().toString().equals("checksums.json")) {
                    continue;
                }
                values.add(Json.map(
                        "path", root.relativize(path).toString().replace('\\', '/'),
                        "sha256", sha256(path),
                        "bytes", size(path)));
            }
        } catch (IOException exception) {
            throw new BenchError("cannot walk " + root + ": " + exception.getMessage(), exception);
        }
        return values;
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new BenchError("cannot stat " + path, exception);
        }
    }
}

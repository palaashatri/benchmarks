package com.palaashatri.bench.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Owned-process lifecycle and framed TCP regressions for the shared runtime. The checks live in the three classes
 * they exercise. Each test runs with a private temporary directory, so artifacts from other runs on the host cannot
 * be mistaken for leftovers of these tests.
 */
class SharedRuntimeTest {
    private String previousTemporaryDirectory;
    private Path temporary;

    @BeforeEach
    void isolateTemporaryDirectory() throws IOException {
        temporary = Files.createTempDirectory("common-runtime-test-");
        previousTemporaryDirectory = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", temporary.toString());
    }

    @AfterEach
    void restoreTemporaryDirectory() {
        System.setProperty("java.io.tmpdir", previousTemporaryDirectory);
    }

    @Test
    void childJvmReadinessCleanupAndIsolationHold() throws Exception {
        LifecycleTest.main(new String[0]);
    }

    @Test
    void jsonRoundTripsTheSharedFormat() {
        JsonTest.main(new String[0]);
    }

    @Test
    void framedTcpSessionsAreBoundedAndReaped() throws Exception {
        TcpLifecycleTest.main(new String[0]);
    }
}

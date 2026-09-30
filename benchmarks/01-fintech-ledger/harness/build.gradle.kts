plugins {
    java
    application
}

group = "com.palaashatri.bench"
version = "0.2.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

application {
    mainClass.set("com.palaashatri.bench.b01.harness.BenchmarkHarness")
}

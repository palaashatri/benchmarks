plugins {
    java
    application
}

group = "com.palaashatri.bench"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

application {
    mainClass.set("com.palaashatri.bench.b06.harness.BenchmarkHarness")
}

sourceSets.main {
    java.srcDir("../../common/src/main/java")
    java.srcDir("../../../tools/smoke/src/main/java")
}

tasks.register<Exec>("smokeTest") { commandLine("sh", "./run.sh", "test") }
tasks.named<Test>("test") { dependsOn("smokeTest") }

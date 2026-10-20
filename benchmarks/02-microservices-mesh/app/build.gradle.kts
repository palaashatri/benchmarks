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
    mainClass.set("com.palaashatri.bench.b02.app.BenchmarkApp")
}

sourceSets.main { java.srcDir("../../common/src/main/java") }

tasks.register<Exec>("architectureTest") { commandLine("sh", "./run.sh", "test") }
tasks.named<Test>("test") { dependsOn("architectureTest") }

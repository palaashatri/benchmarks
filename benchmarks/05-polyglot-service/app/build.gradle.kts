plugins {
    java
    application
}

group = "com.palaashatri.bench"
version = "0.1.0"

// Repositories come from settings.gradle.kts (FAIL_ON_PROJECT_REPOS).

dependencies {
    implementation("org.mozilla:rhino:1.7.15")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

application {
    mainClass.set("com.palaashatri.bench.b05.app.BenchmarkApp")
}

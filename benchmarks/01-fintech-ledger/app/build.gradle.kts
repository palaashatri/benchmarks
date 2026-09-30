plugins {
    java
    application
}

group = "com.palaashatri.bench"
version = "0.2.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation("com.h2database:h2:2.2.224")
    implementation("com.zaxxer:HikariCP:5.1.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.9")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

application {
    mainClass.set("com.palaashatri.bench.b01.app.BenchmarkApp")
}

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
    options.compilerArgs.addAll(listOf(
        "--enable-preview",
        "--add-modules", "jdk.incubator.vector"
    ))
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-preview", "--add-modules=jdk.incubator.vector")
}

application {
    mainClass.set("com.palaashatri.bench.b04.app.BenchmarkApp")
    applicationDefaultJvmArgs = listOf(
        "--enable-preview",
        "--add-modules=jdk.incubator.vector"
    )
}

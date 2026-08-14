import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The optimiser, as its own process.
 *
 * Deliberately not a Spring application. It reads one request from stdin, solves
 * it, writes one response to stdout and exits — so there is no context to boot,
 * no port to bind, and nothing to keep alive between solves. That also keeps the
 * dependency list short enough to see at a glance, which matters for a process
 * whose whole job is to contain a native crash: every jar here is one more thing
 * that could pull a second native library in beside CP-SAT.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

dependencies {
    implementation(libs.ortools.java)
    // Pinned rather than BOM-managed: there is no Spring here to manage it. Kept
    // at the version Boot resolves for plotted-api so both ends of the protocol
    // read and write JSON the same way.
    implementation(libs.jackson.module.kotlin.pinned)

    // The aggregate, so the *engine* comes with the annotations. Without it the
    // classes compile, Gradle reports a green build, and not one test runs --
    // which is the exact shape of failure this project keeps writing down.
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.runner.junit5)
}

/**
 * A plain jar with a manifest entry, plus a directory of its dependencies.
 *
 * The API launches this with `-cp`, so it needs the whole classpath rather than
 * an executable jar — and OR-Tools resolves its natives out of a jar on that
 * classpath, so shading everything into one archive is the arrangement most
 * likely to break the native load. Two artefacts, no repackaging.
 */
tasks.jar {
    archiveFileName = "plotted-solver.jar"
    manifest {
        attributes["Main-Class"] = "app.plotted.solver.WorkerKt"
    }
}

/** Copies the runtime dependencies next to the jar, for the Docker image. */
val collectRuntimeDependencies by tasks.registering(Sync::class) {
    group = "build"
    description = "Stage the solver's runtime dependencies for packaging"
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("libs/dependencies"))
}

tasks.build {
    dependsOn(collectRuntimeDependencies)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

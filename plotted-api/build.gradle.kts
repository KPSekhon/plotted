import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jooq.meta.jaxb.ForcedType
import org.jooq.meta.jaxb.Logging
import org.jooq.meta.jaxb.Property

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.jooq.codegen)
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
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jooq)
    implementation(libs.spring.boot.starter.data.redis)

    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)

    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    implementation(libs.springdoc.openapi)
    implementation(libs.micrometer.prometheus)

    implementation(libs.bouncycastle)
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    developmentOnly(libs.spring.boot.devtools)

    // jOOQ code generation runs against the Flyway migration scripts (see `jooq { }` below),
    // so the build does not require a live database. See docs/adr/0004-jooq-over-jpa.md.
    jooqCodegen(libs.jooq.meta.extensions)

    testImplementation(libs.spring.boot.starter.test) {
        exclude(module = "mockito-core")
    }
    testImplementation(libs.spring.security.test)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}

// ---------------------------------------------------------------------------
// jOOQ code generation
//
// The generator reads the Flyway migrations directly rather than introspecting a
// running database, so `./gradlew build` works on a machine with no Postgres and
// no Docker. Statements the jOOQ parser cannot model (extensions, GiST exclusion
// constraints, partitioning) are fenced with `[jooq ignore start/stop]` comments
// in the migration files; Postgres treats them as comments and applies the DDL.
// ---------------------------------------------------------------------------
val jooqOutputDir = layout.buildDirectory.dir("generated-sources/jooq")

jooq {
    configuration {
        logging = Logging.WARN
        generator {
            name = "org.jooq.codegen.KotlinGenerator"
            database {
                name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                properties.addAll(
                    listOf(
                        Property()
                            .withKey("scripts")
                            .withValue("src/main/resources/db/migration/*.sql"),
                        Property().withKey("sort").withValue("flyway"),
                        Property().withKey("defaultNameCase").withValue("lower"),
                        Property().withKey("unqualifiedSchema").withValue("none"),
                    ),
                )
                // The DDL simulation resolves Postgres `jsonb` to plain `json`.
                // Every JSON-typed column in the schema is really jsonb, so map
                // them back: binding a `json` value into a `jsonb` column relies
                // on a cast Postgres does not perform on assignment.
                forcedTypes.add(
                    ForcedType()
                        .withName("JSONB")
                        .withIncludeTypes("JSON"),
                )
            }
            generate {
                isDeprecated = false
                isRecords = true
                isImmutablePojos = false
                isFluentSetters = true
            }
            target {
                packageName = "app.plotted.generated.jooq"
                directory = jooqOutputDir.get().asFile.path
            }
        }
    }
}

sourceSets.main {
    java.srcDir(jooqOutputDir)
}

tasks.named("compileKotlin") {
    dependsOn(tasks.named("jooqCodegen"))
}

ktlint {
    filter {
        exclude { it.file.path.contains("generated-sources") }
    }
}

// ktlint scans the whole main source set, which now contains the jOOQ output
// directory. The files themselves are filtered out above, but Gradle still sees
// the directory as an input and needs the producing task declared.
tasks.matching { it.name.startsWith("runKtlint") }.configureEach {
    dependsOn(tasks.named("jooqCodegen"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "plotted-api.jar"
}

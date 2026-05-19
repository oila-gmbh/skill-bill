import java.io.File

plugins {
  alias(libs.plugins.ksp)
  application
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  api(project(":runtime-core"))
  implementation(libs.kotlin.inject.runtime)
  implementation(libs.kotlinx.serialization.json)
  // SKILL-48 Subtask 2d: TelemetryEventSchemaValidator wraps the
  // networknt JSON-Schema validator and parses the canonical YAML
  // schema bundled by the copyTelemetryEventSchema task below. Pulling
  // the validator into runtime-mcp (alongside McpToolRegistry) keeps the
  // telemetry parse seams co-located with the source of truth for event
  // names (`McpToolRegistry.toolNames`).
  implementation(libs.json.schema.validator)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.dataformat.yaml)
  ksp(libs.kotlin.inject.compiler)
  testImplementation(project(":runtime-cli"))
  // SKILL-48 Subtask 2d: pull in the shared `skillbill.testing.repoRootFromTest()`
  // helper so dedicated parity/violations tests can locate the canonical
  // schema YAML on disk without re-declaring a local helper (C8).
  testImplementation(testFixtures(project(":runtime-core")))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}

application {
  mainClass.set("skillbill.mcp.MainKt")
}

tasks.named<JavaExec>("run") {
  standardInput = System.`in`
}

// SKILL-48 Subtask 2d: copy the canonical telemetry-event JSON Schema
// into the runtime-mcp resources at build time. The on-disk file under
// `orchestration/contracts/` remains the source of truth for the
// desktop schema viewer; this task pulls the same bytes into the JVM
// classpath so the runtime validator can locate the schema without
// depending on the runtime working directory matching the repo root.
//
// These paths MUST mirror `TelemetryEventSchemaPaths.REPO_RELATIVE_PATH`
// and `TelemetryEventSchemaPaths.CLASSPATH_RESOURCE` in
// `runtime-mcp/src/main/kotlin/skillbill/mcp/TelemetryEventSchemaPaths.kt`.
// Gradle's Kotlin DSL cannot import runtime constants; if these strings
// drift the runtime loader will loud-fail because the classpath resource
// will be absent at the expected location.
//
// AGENTS.md F-101: the file-existence guard runs at task EXECUTION
// time inside `doFirst {}` (not at configuration time) so the
// configuration cache stays warm across `gradle help` / IDE syncs.
// The schema path is resolved into a primitive `String` BEFORE the
// doFirst closure captures it — capturing a Gradle script-scope
// `File` reference inside doFirst fails configuration-cache
// serialization, but capturing a plain String is safe.
val canonicalTelemetryEventSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/telemetry-event-schema.yaml")
    .absolutePath

val copyTelemetryEventSchema =
  tasks.register<Copy>("copyTelemetryEventSchema") {
    val schemaPath = canonicalTelemetryEventSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-48: canonical telemetry-event schema is missing at $schemaPath. " +
          "Run from the repo root and ensure the schema file exists."
      }
    }
  }

sourceSets.named("main") {
  resources.srcDir(layout.buildDirectory.dir("generated/skillbill-contracts"))
}

tasks.named("processResources") {
  dependsOn(copyTelemetryEventSchema)
}

tasks.named("processTestResources") {
  dependsOn(copyTelemetryEventSchema)
}

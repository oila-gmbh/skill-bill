import java.io.File

plugins {
  alias(libs.plugins.ksp)
  application
  id("skillbill.jvm-library")
  id("skillbill.quality")
  // SKILL-55 subtask 1 (F-004/F-005): self-contained jlink image wiring is hoisted into
  // the skillbill.runtime-image convention plugin. It applies org.beryx.runtime, the
  // shared module set (incl. java.net.http), the lazy Java 17 link toolchain, the
  // versioned imageZip name, the sha256 sidecar, and the CC opt-out. We only declare the
  // varying input below: the launcher / archive base name.
  id("skillbill.runtime-image")
}

dependencies {
  // SKILL-52.2 subtask 5: narrowed allow-list pinned by
  // `runtime-core/src/test/kotlin/skillbill/architecture/RuntimeAdapterDependencyAllowlistTest.kt`.
  // runtime-infra-fs / runtime-infra-http were dropped — runtime-mcp has no
  // concrete `skillbill.infrastructure.*` imports outside test sources; the
  // infrastructure adapters are resolved through `RuntimeComponent` (kotlin-inject).
  implementation(project(":runtime-application"))
  implementation(project(":runtime-contracts"))
  implementation(project(":runtime-core"))
  implementation(project(":runtime-domain"))
  implementation(project(":runtime-ports"))
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
  // SKILL-52.2 subtask 5: runtime-infra-fs / runtime-infra-http stay on the
  // test classpath because `RuntimeModuleSmokeTest` imports concrete runtime
  // classes (`InstallRuntime`, `LauncherRuntime`, `NativeAgentRuntime`,
  // `ScaffoldRuntime`, `TelemetryRuntime`). Test code crossing module boundaries
  // for fixtures is expected; main source must not.
  testImplementation(project(":runtime-infra-fs"))
  testImplementation(project(":runtime-infra-http"))
  testImplementation(project(":runtime-cli"))
  testImplementation(project(":runtime-infra-sqlite"))
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

// SKILL-55 subtask 1: self-contained runtime image via the skillbill.runtime-image
// convention plugin (Badass Runtime / jlink). Mirrors runtime-cli; the plugin wraps the
// existing `application` installDist distribution, so the image keeps the `bin/runtime-mcp`
// launcher (= applicationName) that subtask 4 will symlink to `skill-bill-mcp` (AC3).
// `application` + installDist stay intact.
//
// F-003: the generated telemetry-event JSON Schema reaches the image through the NORMAL
// distribution pipeline — `copyTelemetryEventSchema` (wired into processResources + main
// resources above) -> `jar` -> `installDist` -> Badass `runtime` (which dependsOn
// installDist) -> `runtimeZip`. The Badass `jre` task only links the trimmed JDK (no app
// code), so a `jre.dependsOn(processResources)` hook is a no-op for bundling the resource
// and is intentionally NOT used. installDist already bundles the resource into the
// runtime-mcp jar at skillbill/contracts/telemetry-event-schema.yaml, so no extra wiring
// is needed here.
runtimeImage {
  imageBaseName.set("runtime-mcp")
}

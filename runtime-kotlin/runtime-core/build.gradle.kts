import org.gradle.api.tasks.testing.Test
import java.io.File

plugins {
  alias(libs.plugins.ksp)
  id("skillbill.jvm-library")
  id("skillbill.quality")
  // SKILL-48 C8: publish the shared `repoRootFromTest()` helper to downstream test code
  // (runtime-core's own tests and runtime-desktop:feature:skillbill jvmTest) via the
  // `java-test-fixtures` plugin so the four prior copies collapse into one source.
  `java-test-fixtures`
}

dependencies {
  api(project(":runtime-application"))
  api(project(":runtime-contracts"))
  api(project(":runtime-domain"))
  api(project(":runtime-infra-fs"))
  api(project(":runtime-infra-http"))
  api(project(":runtime-infra-sqlite"))
  api(project(":runtime-ports"))
  implementation(libs.snakeyaml)
  implementation(libs.json.schema.validator)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.dataformat.yaml)
  implementation(libs.kotlin.inject.runtime)
  ksp(libs.kotlin.inject.compiler)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}

// SKILL-47: copy the canonical platform-pack JSON Schema into the runtime-core
// resources at build time. The on-disk file under `orchestration/contracts/`
// remains the source of truth for the desktop schema viewer; this task pulls
// the same bytes into the JVM classpath so the runtime validator can locate
// the schema without depending on the runtime working directory matching the
// repo root.
//
// F-008: these paths MUST mirror `PlatformPackSchemaPaths.REPO_RELATIVE_PATH`
// and `PlatformPackSchemaPaths.CLASSPATH_RESOURCE` in
// `runtime-core/src/main/kotlin/skillbill/scaffold/PlatformPackSchemaValidator.kt`.
// Gradle's Kotlin DSL cannot import runtime constants; if these strings drift
// the runtime loader will loud-fail because the classpath resource will be
// absent at the expected location.
val canonicalPlatformPackSchema =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/platform-pack-schema.yaml")

// SKILL-48 C6: fail at configure-time when the canonical schema is missing. Previously
// a misconfigured checkout would only surface as a missing classpath resource at runtime;
// pulling the existence check up to configure-time means `gradle help` already loud-fails
// with a path-bearing message instead of producing a silently empty resource.
require(canonicalPlatformPackSchema.exists()) {
  val absolutePath = canonicalPlatformPackSchema.absolutePath
  "SKILL-48: canonical platform-pack schema is missing at $absolutePath. " +
    "Run from the repo root and ensure `orchestration/contracts/platform-pack-schema.yaml` exists."
}

val copyPlatformPackSchema =
  tasks.register<Copy>("copyPlatformPackSchema") {
    from(canonicalPlatformPackSchema)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
  }

// SKILL-48 Subtask 2c: copy the canonical native-agent composition JSON
// Schema into the runtime-core resources at build time. Mirrors the
// workflow-state / install-plan Copy tasks in
// `runtime-domain/build.gradle.kts` (same `inputs.file` + `doFirst`
// pattern so the configuration cache stays warm). Path strings must
// mirror `NativeAgentCompositionSchemaPaths.REPO_RELATIVE_PATH` and
// `NativeAgentCompositionSchemaPaths.CLASSPATH_RESOURCE`.
//
// AGENTS.md F-101: resolve the schema path into a primitive `String`
// BEFORE the doFirst closure captures it — capturing a Gradle
// script-scope `File` reference inside doFirst fails configuration-cache
// serialization, but capturing a plain String is safe.
val canonicalNativeAgentCompositionSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/native-agent-composition-schema.yaml")
    .absolutePath

val copyNativeAgentCompositionSchema =
  tasks.register<Copy>("copyNativeAgentCompositionSchema") {
    val schemaPath = canonicalNativeAgentCompositionSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-48: canonical native-agent composition schema is missing at $schemaPath. " +
          "Run from the repo root and ensure the schema file exists."
      }
    }
  }

sourceSets.named("main") {
  resources.srcDir(layout.buildDirectory.dir("generated/skillbill-contracts"))
}

tasks.named("processResources") {
  dependsOn(copyPlatformPackSchema)
  dependsOn(copyNativeAgentCompositionSchema)
}

tasks.named("processTestResources") {
  dependsOn(copyPlatformPackSchema)
  dependsOn(copyNativeAgentCompositionSchema)
}

tasks.withType<Test>().configureEach {
  if (project.hasProperty("update-snapshots")) {
    systemProperty("update-snapshots", "true")
  }
}

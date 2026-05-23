import org.gradle.api.tasks.testing.Test
import java.io.File

plugins {
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  implementation(project(":runtime-contracts"))
  // SKILL-48 Subtask 2a: WorkflowStateSchemaValidator wraps the networknt
  // JSON-Schema validator and parses the canonical YAML schema bundled by
  // the copyWorkflowStateSchema task below. Pulling the validator into
  // runtime-domain (not runtime-core) keeps the dependency direction
  // clean — runtime-application + runtime-domain already need to validate
  // at their parse seams, and runtime-core can still reach the validator
  // via its existing `api(project(":runtime-domain"))` edge.
  implementation(libs.json.schema.validator)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.dataformat.yaml)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}

// SKILL-48 Subtask 2a: copy the canonical workflow-state JSON Schema into
// the runtime-domain resources at build time. The on-disk file under
// `orchestration/contracts/` remains the source of truth for the desktop
// schema viewer; this task pulls the same bytes into the JVM classpath
// so the runtime validator can locate the schema without depending on
// the runtime working directory matching the repo root.
//
// These paths MUST mirror `WorkflowStateSchemaPaths.REPO_RELATIVE_PATH`
// and `WorkflowStateSchemaPaths.CLASSPATH_RESOURCE` in
// `runtime-domain/src/main/kotlin/skillbill/workflow/WorkflowStateSchemaPaths.kt`.
// Gradle's Kotlin DSL cannot import runtime constants; if these strings
// drift the runtime loader will loud-fail because the classpath resource
// will be absent at the expected location.
//
// SKILL-48 Subtask 2a (AC8 / F-101): the file-existence guard runs at
// task EXECUTION time inside `doFirst {}` (not at configuration time)
// so the configuration cache stays warm across `gradle help` / IDE
// syncs. The schema path is resolved into a primitive `String` BEFORE
// the doFirst closure captures it — capturing a Gradle script-scope
// `File` reference inside doFirst fails configuration-cache
// serialization, but capturing a plain String is safe.
val canonicalWorkflowStateSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/workflow-state-schema.yaml")
    .absolutePath

val copyWorkflowStateSchema =
  tasks.register<Copy>("copyWorkflowStateSchema") {
    val schemaPath = canonicalWorkflowStateSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-48: canonical workflow-state schema is missing at $schemaPath. " +
          "Run from the repo root and ensure the schema file exists."
      }
    }
  }

// SKILL-48 Subtask 2b: copy the canonical install-plan JSON Schema into
// the runtime-domain resources at build time. Mirrors the workflow-state
// Copy task above (same `inputs.file` + `doFirst` pattern so the
// configuration cache stays warm). Path strings must mirror
// `InstallPlanSchemaPaths.REPO_RELATIVE_PATH` and
// `InstallPlanSchemaPaths.CLASSPATH_RESOURCE`.
val canonicalInstallPlanSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/install-plan-schema.yaml")
    .absolutePath

val copyInstallPlanSchema =
  tasks.register<Copy>("copyInstallPlanSchema") {
    val schemaPath = canonicalInstallPlanSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-48: canonical install-plan schema is missing at $schemaPath. " +
          "Run from the repo root and ensure the schema file exists."
      }
    }
  }

// SKILL-51 Subtask 1: copy the canonical decomposition-manifest JSON
// Schema into runtime-domain resources. This mirrors the workflow-state
// and install-plan Copy tasks above: path strings must stay aligned
// with `DecompositionManifestSchemaPaths`, and the existence guard runs
// at task execution time so configuration-cache reads stay clean.
val canonicalDecompositionManifestSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/decomposition-manifest-schema.yaml")
    .absolutePath

val copyDecompositionManifestSchema =
  tasks.register<Copy>("copyDecompositionManifestSchema") {
    val schemaPath = canonicalDecompositionManifestSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-51: canonical decomposition manifest schema is missing at $schemaPath. " +
          "Run from the repo root and ensure the schema file exists."
      }
    }
  }

sourceSets.named("main") {
  resources.srcDir(layout.buildDirectory.dir("generated/skillbill-contracts"))
}

tasks.named("processResources") {
  dependsOn(copyWorkflowStateSchema)
  dependsOn(copyInstallPlanSchema)
  dependsOn(copyDecompositionManifestSchema)
}

tasks.named("processTestResources") {
  dependsOn(copyWorkflowStateSchema)
  dependsOn(copyInstallPlanSchema)
  dependsOn(copyDecompositionManifestSchema)
}

tasks.withType<Test>().configureEach {
  if (project.hasProperty("update-snapshots")) {
    systemProperty("update-snapshots", "true")
  }
}

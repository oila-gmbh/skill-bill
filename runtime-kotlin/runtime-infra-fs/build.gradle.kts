import java.io.File

plugins {
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  api(project(":runtime-ports"))
  implementation(project(":runtime-contracts"))
  implementation(project(":runtime-domain"))
  implementation(libs.kotlin.inject.runtime)
  implementation(libs.snakeyaml)
  implementation(libs.json.schema.validator)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.dataformat.yaml)
  implementation(libs.jna)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}

val canonicalPlatformPackSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/platform-pack-schema.yaml")
    .absolutePath

val copyPlatformPackSchema =
  tasks.register<Copy>("copyPlatformPackSchema") {
    val schemaPath = canonicalPlatformPackSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-52: canonical platform-pack schema is missing at $schemaPath. " +
          "Run from the repo root and ensure " +
          "`orchestration/contracts/platform-pack-schema.yaml` exists."
      }
    }
  }

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
        "SKILL-52: canonical native-agent composition schema is missing at $schemaPath. " +
          "Run from the repo root and ensure the schema file exists."
      }
    }
  }

// SKILL-52.3 Subtask 1: the three schema validators moved here from
// runtime-contracts, so the schema resources they read at runtime must
// ship on this module's classpath alongside the platform-pack and
// native-agent composition schemas above.
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
        "SKILL-52: canonical workflow-state schema is missing at $schemaPath. " +
          "Run from the repo root and ensure the schema file exists."
      }
    }
  }

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
        "SKILL-52: canonical install-plan schema is missing at $schemaPath. " +
          "Run from the repo root and ensure the schema file exists."
      }
    }
  }

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
        "SKILL-52: canonical decomposition manifest schema is missing at $schemaPath. " +
          "Run from the repo root and ensure the schema file exists."
      }
    }
  }

val canonicalGoalObservabilityEventSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/goal-observability-event-schema.yaml")
    .absolutePath

val copyGoalObservabilityEventSchema =
  tasks.register<Copy>("copyGoalObservabilityEventSchema") {
    val schemaPath = canonicalGoalObservabilityEventSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-61: canonical goal-observability event schema is missing at $schemaPath. " +
          "Run from the repo root and ensure the schema file exists."
      }
    }
  }

val canonicalGoalProgressEventSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/goal-progress-event-schema.yaml")
    .absolutePath

val copyGoalProgressEventSchema =
  tasks.register<Copy>("copyGoalProgressEventSchema") {
    val schemaPath = canonicalGoalProgressEventSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-64: canonical goal progress event schema is missing at $schemaPath. " +
          "Run from the repo root and ensure the schema file exists."
      }
    }
  }

val canonicalFeatureTaskRuntimePhaseOutputSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/feature-task-runtime-phase-output-schema.yaml")
    .absolutePath

val copyFeatureTaskRuntimePhaseOutputSchema =
  tasks.register<Copy>("copyFeatureTaskRuntimePhaseOutputSchema") {
    val schemaPath = canonicalFeatureTaskRuntimePhaseOutputSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-65: canonical feature-task-runtime phase output schema is missing at $schemaPath. " +
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
  dependsOn(copyWorkflowStateSchema)
  dependsOn(copyInstallPlanSchema)
  dependsOn(copyDecompositionManifestSchema)
  dependsOn(copyGoalObservabilityEventSchema)
  dependsOn(copyGoalProgressEventSchema)
  dependsOn(copyFeatureTaskRuntimePhaseOutputSchema)
}

tasks.named("processTestResources") {
  dependsOn(copyPlatformPackSchema)
  dependsOn(copyNativeAgentCompositionSchema)
  dependsOn(copyWorkflowStateSchema)
  dependsOn(copyInstallPlanSchema)
  dependsOn(copyDecompositionManifestSchema)
  dependsOn(copyGoalObservabilityEventSchema)
  dependsOn(copyGoalProgressEventSchema)
  dependsOn(copyFeatureTaskRuntimePhaseOutputSchema)
}

tasks.withType<Test>().configureEach {
  if (project.hasProperty("update-snapshots")) {
    systemProperty("update-snapshots", "true")
  }
}

tasks.register<JavaExec>("platformPackSubstanceReport") {
  group = "verification"
  description = "Emit the maintained platform-pack substance report in text or JSON form."
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("skillbill.scaffold.substance.PlatformPackSubstanceReportMainKt")
  args(
    "--repo-root=${providers.gradleProperty(
      "repoRoot",
    ).orElse(rootProject.projectDir.parentFile.absolutePath).get()}",
    "--format=${providers.gradleProperty("reportFormat").orElse("text").get()}",
  )
}

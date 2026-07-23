import java.io.File

plugins {
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  api(project(":runtime-ports"))
  api(project(":runtime-domain"))
  implementation(project(":runtime-contracts"))
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

val canonicalAgentAddonSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/agent-addon-schema.yaml")
    .absolutePath

val copyAgentAddonSchema =
  tasks.register<Copy>("copyAgentAddonSchema") {
    val schemaPath = canonicalAgentAddonSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-122: canonical agent-addon schema is missing at $schemaPath."
      }
    }
  }

val canonicalReviewContextSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/review-context-schema.yaml").absolutePath
val copyReviewContextSchema =
  tasks.register<Copy>("copyReviewContextSchema") {
    val schemaPath = canonicalReviewContextSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-125: canonical review-context schema is missing at $schemaPath."
      }
    }
  }

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

val canonicalNativeAgentLinkInventorySchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/native-agent-link-inventory-schema.yaml")
    .absolutePath

val copyNativeAgentLinkInventorySchema =
  tasks.register<Copy>("copyNativeAgentLinkInventorySchema") {
    val schemaPath = canonicalNativeAgentLinkInventorySchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-129: canonical native-agent link inventory schema is missing at $schemaPath."
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

val canonicalGoalSubtaskReviewStateSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/goal-subtask-review-state-schema.yaml")
    .absolutePath

val copyGoalSubtaskReviewStateSchema =
  tasks.register<Copy>("copyGoalSubtaskReviewStateSchema") {
    val schemaPath = canonicalGoalSubtaskReviewStateSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-119: canonical goal-subtask review-state schema is missing at $schemaPath."
      }
    }
  }

val canonicalFeatureTaskRuntimePhaseOutputSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/feature-task-runtime-phase-output-schema.yaml")
    .absolutePath

val canonicalFeatureTaskExecutionIdentitySchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/feature-task-execution-identity-schema.yaml")
    .absolutePath

val canonicalFeatureTaskRuntimeWorkerOwnershipSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/feature-task-runtime-worker-ownership-schema.yaml")
    .absolutePath

val copyFeatureTaskRuntimeWorkerOwnershipSchema =
  tasks.register<Copy>("copyFeatureTaskRuntimeWorkerOwnershipSchema") {
    val schemaPath = canonicalFeatureTaskRuntimeWorkerOwnershipSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-120: canonical feature-task runtime worker-ownership schema is missing " +
          "at $schemaPath."
      }
    }
  }

val copyFeatureTaskExecutionIdentitySchema =
  tasks.register<Copy>("copyFeatureTaskExecutionIdentitySchema") {
    val schemaPath = canonicalFeatureTaskExecutionIdentitySchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-120: canonical feature-task execution-identity schema is missing at $schemaPath."
      }
    }
  }

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

val canonicalFeatureTaskRuntimeHandoffEnvelopeSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/feature-task-runtime-handoff-envelope-schema.yaml")
    .absolutePath

val copyFeatureTaskRuntimeHandoffEnvelopeSchema =
  tasks.register<Copy>("copyFeatureTaskRuntimeHandoffEnvelopeSchema") {
    val schemaPath = canonicalFeatureTaskRuntimeHandoffEnvelopeSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-137: canonical handoff-envelope schema is missing at $schemaPath."
      }
    }
  }

val canonicalFeatureTaskRuntimeAuditRepairPlanSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/feature-task-runtime-audit-repair-plan-schema.yaml")
    .absolutePath

val copyFeatureTaskRuntimeAuditRepairPlanSchema =
  tasks.register<Copy>("copyFeatureTaskRuntimeAuditRepairPlanSchema") {
    val schemaPath = canonicalFeatureTaskRuntimeAuditRepairPlanSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-131: canonical audit-repair-plan schema is missing at $schemaPath."
      }
    }
  }

val canonicalGoalPlanningPreparationSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/goal-planning-preparation-schema.yaml")
    .absolutePath

val copyGoalPlanningPreparationSchema =
  tasks.register<Copy>("copyGoalPlanningPreparationSchema") {
    val schemaPath = canonicalGoalPlanningPreparationSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-128: canonical goal planning preparation schema is missing at $schemaPath."
      }
    }
  }

sourceSets.named("main") {
  resources.srcDir(layout.buildDirectory.dir("generated/skillbill-contracts"))
}

tasks.named("processResources") {
  dependsOn(copyAgentAddonSchema)
  dependsOn(copyReviewContextSchema)
  dependsOn(copyPlatformPackSchema)
  dependsOn(copyNativeAgentCompositionSchema)
  dependsOn(copyNativeAgentLinkInventorySchema)
  dependsOn(copyWorkflowStateSchema)
  dependsOn(copyInstallPlanSchema)
  dependsOn(copyDecompositionManifestSchema)
  dependsOn(copyGoalObservabilityEventSchema)
  dependsOn(copyGoalProgressEventSchema)
  dependsOn(copyGoalSubtaskReviewStateSchema)
  dependsOn(copyFeatureTaskRuntimePhaseOutputSchema)
  dependsOn(copyFeatureTaskRuntimeAuditRepairPlanSchema)
  dependsOn(copyFeatureTaskRuntimeHandoffEnvelopeSchema)
  dependsOn(copyFeatureTaskExecutionIdentitySchema)
  dependsOn(copyFeatureTaskRuntimeWorkerOwnershipSchema)
  dependsOn(copyGoalPlanningPreparationSchema)
}

tasks.named("processTestResources") {
  dependsOn(copyAgentAddonSchema)
  dependsOn(copyReviewContextSchema)
  dependsOn(copyPlatformPackSchema)
  dependsOn(copyNativeAgentCompositionSchema)
  dependsOn(copyNativeAgentLinkInventorySchema)
  dependsOn(copyWorkflowStateSchema)
  dependsOn(copyInstallPlanSchema)
  dependsOn(copyDecompositionManifestSchema)
  dependsOn(copyGoalObservabilityEventSchema)
  dependsOn(copyGoalProgressEventSchema)
  dependsOn(copyGoalSubtaskReviewStateSchema)
  dependsOn(copyFeatureTaskRuntimePhaseOutputSchema)
  dependsOn(copyFeatureTaskRuntimeAuditRepairPlanSchema)
  dependsOn(copyFeatureTaskRuntimeHandoffEnvelopeSchema)
  dependsOn(copyFeatureTaskExecutionIdentitySchema)
  dependsOn(copyFeatureTaskRuntimeWorkerOwnershipSchema)
  dependsOn(copyGoalPlanningPreparationSchema)
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

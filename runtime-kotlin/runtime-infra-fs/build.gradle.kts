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

val canonicalSpecialistContractPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/review-orchestrator/specialist-contract.md")
    .absolutePath

val copySpecialistContract =
  tasks.register<Copy>("copySpecialistContract") {
    val contractPath = canonicalSpecialistContractPath
    from(contractPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/review"))
    inputs.file(contractPath)
    doFirst {
      require(File(contractPath).isFile) {
        "Authoritative delegated-review specialist contract is missing at $contractPath."
      }
    }
  }

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

val canonicalRejectedOutputDiagnosticSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/rejected-output-diagnostic-schema.yaml")
    .absolutePath

val copyRejectedOutputDiagnosticSchema =
  tasks.register<Copy>("copyRejectedOutputDiagnosticSchema") {
    val schemaPath = canonicalRejectedOutputDiagnosticSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-134: canonical rejected-output diagnostic schema is missing at $schemaPath."
      }
    }
  }

val canonicalProducerOutputEvidenceSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/producer-output-evidence-schema.yaml")
    .absolutePath

val copyProducerOutputEvidenceSchema =
  tasks.register<Copy>("copyProducerOutputEvidenceSchema") {
    val schemaPath = canonicalProducerOutputEvidenceSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-152: canonical producer output evidence schema is missing at $schemaPath."
      }
    }
  }

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

val canonicalFeatureTaskRuntimePhaseLaunchBriefingSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/feature-task-runtime-phase-launch-briefing-schema.yaml")
    .absolutePath

val copyFeatureTaskRuntimePhaseLaunchBriefingSchema =
  tasks.register<Copy>("copyFeatureTaskRuntimePhaseLaunchBriefingSchema") {
    val schemaPath = canonicalFeatureTaskRuntimePhaseLaunchBriefingSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-146: canonical phase-launch-briefing schema is missing at $schemaPath."
      }
    }
  }

val canonicalFeatureTaskRuntimePhaseHandoffSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/feature-task-runtime-phase-handoff-schema.yaml")
    .absolutePath

val copyFeatureTaskRuntimePhaseHandoffSchema =
  tasks.register<Copy>("copyFeatureTaskRuntimePhaseHandoffSchema") {
    val schemaPath = canonicalFeatureTaskRuntimePhaseHandoffSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-146: canonical phase-handoff schema is missing at $schemaPath."
      }
    }
  }

val canonicalFeatureTaskRuntimePersistenceSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/feature-task-runtime-persistence-schema.yaml")
    .absolutePath

val copyFeatureTaskRuntimePersistenceSchema =
  tasks.register<Copy>("copyFeatureTaskRuntimePersistenceSchema") {
    val schemaPath = canonicalFeatureTaskRuntimePersistenceSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-146: canonical feature-task-runtime persistence schema is missing at $schemaPath."
      }
    }
  }

val canonicalFeatureTaskRuntimeProjectionMeasurementSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/feature-task-runtime-projection-measurement-schema.yaml")
    .absolutePath

val copyFeatureTaskRuntimeProjectionMeasurementSchema =
  tasks.register<Copy>("copyFeatureTaskRuntimeProjectionMeasurementSchema") {
    val schemaPath = canonicalFeatureTaskRuntimeProjectionMeasurementSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-146: canonical projection-measurement schema is missing at $schemaPath."
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

val canonicalFeatureTaskRuntimePlanningProjectionsSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/feature-task-runtime-planning-projections-schema.yaml")
    .absolutePath

val copyFeatureTaskRuntimePlanningProjectionsSchema =
  tasks.register<Copy>("copyFeatureTaskRuntimePlanningProjectionsSchema") {
    val schemaPath = canonicalFeatureTaskRuntimePlanningProjectionsSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-137: canonical planning-projections schema is missing at $schemaPath."
      }
    }
  }

val canonicalFeatureTaskRuntimeImplementationAttemptSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/feature-task-runtime-implementation-attempt-schema.yaml")
    .absolutePath

val copyFeatureTaskRuntimeImplementationAttemptSchema =
  tasks.register<Copy>("copyFeatureTaskRuntimeImplementationAttemptSchema") {
    val schemaPath = canonicalFeatureTaskRuntimeImplementationAttemptSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-150: canonical implementation-attempt schema is missing at $schemaPath."
      }
    }
  }

val canonicalFeatureTaskRuntimeAuditGenerationSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/feature-task-runtime-audit-generation-schema.yaml")
    .absolutePath

val copyFeatureTaskRuntimeAuditGenerationSchema =
  tasks.register<Copy>("copyFeatureTaskRuntimeAuditGenerationSchema") {
    val schemaPath = canonicalFeatureTaskRuntimeAuditGenerationSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-150: canonical audit-generation schema is missing at $schemaPath."
      }
    }
  }

val canonicalFeatureTaskRuntimeCheckpointIdentitySchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/feature-task-runtime-checkpoint-identity-schema.yaml")
    .absolutePath

val copyFeatureTaskRuntimeCheckpointIdentitySchema =
  tasks.register<Copy>("copyFeatureTaskRuntimeCheckpointIdentitySchema") {
    val schemaPath = canonicalFeatureTaskRuntimeCheckpointIdentitySchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-150: canonical checkpoint-identity schema is missing at $schemaPath."
      }
    }
  }

val canonicalFeatureTaskRuntimeQuarantineSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/feature-task-runtime-quarantine-schema.yaml")
    .absolutePath

val copyFeatureTaskRuntimeQuarantineSchema =
  tasks.register<Copy>("copyFeatureTaskRuntimeQuarantineSchema") {
    val schemaPath = canonicalFeatureTaskRuntimeQuarantineSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-140: canonical quarantine schema is missing at $schemaPath."
      }
    }
  }

sourceSets.named("main") {
  resources.srcDir(layout.buildDirectory.dir("generated/skillbill-contracts"))
}

tasks.named("processResources") {
  dependsOn(copySpecialistContract)
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
  dependsOn(copyRejectedOutputDiagnosticSchema)
  dependsOn(copyProducerOutputEvidenceSchema)
  dependsOn(copyFeatureTaskRuntimeAuditRepairPlanSchema)
  dependsOn(copyFeatureTaskRuntimeHandoffEnvelopeSchema)
  dependsOn(copyFeatureTaskRuntimePhaseLaunchBriefingSchema)
  dependsOn(copyFeatureTaskRuntimePhaseHandoffSchema)
  dependsOn(copyFeatureTaskRuntimePersistenceSchema)
  dependsOn(copyFeatureTaskRuntimeProjectionMeasurementSchema)
  dependsOn(copyFeatureTaskExecutionIdentitySchema)
  dependsOn(copyFeatureTaskRuntimeWorkerOwnershipSchema)
  dependsOn(copyGoalPlanningPreparationSchema)
  dependsOn(copyFeatureTaskRuntimePlanningProjectionsSchema)
  dependsOn(copyFeatureTaskRuntimeQuarantineSchema)
  dependsOn(copyFeatureTaskRuntimeImplementationAttemptSchema)
  dependsOn(copyFeatureTaskRuntimeAuditGenerationSchema)
  dependsOn(copyFeatureTaskRuntimeCheckpointIdentitySchema)
}

tasks.named("processTestResources") {
  dependsOn(copySpecialistContract)
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
  dependsOn(copyFeatureTaskRuntimePhaseLaunchBriefingSchema)
  dependsOn(copyFeatureTaskRuntimePhaseHandoffSchema)
  dependsOn(copyFeatureTaskRuntimePersistenceSchema)
  dependsOn(copyFeatureTaskRuntimeProjectionMeasurementSchema)
  dependsOn(copyFeatureTaskExecutionIdentitySchema)
  dependsOn(copyFeatureTaskRuntimeWorkerOwnershipSchema)
  dependsOn(copyGoalPlanningPreparationSchema)
  dependsOn(copyFeatureTaskRuntimePlanningProjectionsSchema)
  dependsOn(copyFeatureTaskRuntimeQuarantineSchema)
  dependsOn(copyFeatureTaskRuntimeImplementationAttemptSchema)
  dependsOn(copyFeatureTaskRuntimeAuditGenerationSchema)
  dependsOn(copyFeatureTaskRuntimeCheckpointIdentitySchema)
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

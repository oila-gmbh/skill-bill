package skillbill.contracts.workflow

/**
 * Runtime-side mirror of the schema's `contract_version`; a parity test fails
 * the build if they diverge. Bump both sites together.
 */
const val FEATURE_TASK_RUNTIME_CONTRACT_VERSION: String = "0.3"

/**
 * Single source of truth for where the canonical feature-task-runtime
 * per-phase output schema lives. The Gradle copy task in
 * `runtime-infra-fs/build.gradle.kts` must mirror these values because
 * Gradle's Kotlin DSL cannot import runtime constants directly.
 */
object FeatureTaskRuntimePhaseOutputSchemaPaths {
  /** Repo-relative path to the canonical schema YAML on disk. */
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/feature-task-runtime-phase-output-schema.yaml"

  /** Classpath resource path where runtime-infra-fs bundles the schema for runtime loads. */
  const val CLASSPATH_RESOURCE: String =
    "skillbill/contracts/feature-task-runtime-phase-output-schema.yaml"

  /**
   * Expected value of the canonical schema's `$id`. The validator
   * asserts this value on load so stale or shadowed resources fail
   * loudly at the parse seam.
   */
  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/feature-task-runtime-phase-output-schema.yaml"
}

/**
 * Runtime-side mirror of the handoff-envelope schema's `contract_version`;
 * `FeatureTaskRuntimeHandoffEnvelopeSchemaContractVersionTest` fails the build if they diverge.
 */
const val FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION: String = "0.1"

object FeatureTaskRuntimeHandoffEnvelopeSchemaPaths {
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/feature-task-runtime-handoff-envelope-schema.yaml"
  const val CLASSPATH_RESOURCE: String =
    "skillbill/contracts/feature-task-runtime-handoff-envelope-schema.yaml"
  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/feature-task-runtime-handoff-envelope-schema.yaml"
}

const val FEATURE_TASK_RUNTIME_PHASE_LAUNCH_BRIEFING_CONTRACT_VERSION: String = "0.1"

object FeatureTaskRuntimePhaseLaunchBriefingSchemaPaths {
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/feature-task-runtime-phase-launch-briefing-schema.yaml"
  const val CLASSPATH_RESOURCE: String =
    "skillbill/contracts/feature-task-runtime-phase-launch-briefing-schema.yaml"
  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/feature-task-runtime-phase-launch-briefing-schema.yaml"
}

/**
 * The declaration contract is intentionally versioned independently from the delivered envelope.
 * Version 0.2 is incompatible with the pre-SKILL-146 implicit declaration shape: source, budget,
 * visibility, checkpoint policy, and producer iteration are all mandatory closed-world fields.
 */
const val FEATURE_TASK_RUNTIME_PHASE_HANDOFF_CONTRACT_VERSION: String = "0.2"

object FeatureTaskRuntimePhaseHandoffSchemaPaths {
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/feature-task-runtime-phase-handoff-schema.yaml"
  const val CLASSPATH_RESOURCE: String =
    "skillbill/contracts/feature-task-runtime-phase-handoff-schema.yaml"
  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/feature-task-runtime-phase-handoff-schema.yaml"
}

/**
 * Pins the durable private-phase-record/delivered-projection wire split. Legacy phase records
 * without the explicit record kind and version are not compatible.
 */
const val FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION: String = "0.2"

object FeatureTaskRuntimePersistenceSchemaPaths {
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/feature-task-runtime-persistence-schema.yaml"
  const val CLASSPATH_RESOURCE: String =
    "skillbill/contracts/feature-task-runtime-persistence-schema.yaml"
  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/feature-task-runtime-persistence-schema.yaml"
}

const val FEATURE_TASK_RUNTIME_PROJECTION_MEASUREMENT_CONTRACT_VERSION: String = "0.1"

object FeatureTaskRuntimeProjectionMeasurementSchemaPaths {
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/feature-task-runtime-projection-measurement-schema.yaml"
  const val CLASSPATH_RESOURCE: String =
    "skillbill/contracts/feature-task-runtime-projection-measurement-schema.yaml"
  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/feature-task-runtime-projection-measurement-schema.yaml"
}

/**
 * Runtime-side mirror of the planning-projections schema's `contract_version`;
 * `FeatureTaskRuntimePlanningProjectionsSchemaContractVersionTest` fails the build if they diverge.
 * Pins the four concrete bounded projections (preplanning digest, executable plan, plan commitment,
 * implementation receipt) that replace the coarse whole-receipt projection on the plan/implement/audit edges.
 */
const val FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION: String = "0.1"

object FeatureTaskRuntimePlanningProjectionsSchemaPaths {
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/feature-task-runtime-planning-projections-schema.yaml"
  const val CLASSPATH_RESOURCE: String =
    "skillbill/contracts/feature-task-runtime-planning-projections-schema.yaml"
  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/feature-task-runtime-planning-projections-schema.yaml"
}

/**
 * Runtime-side mirror of the quarantine schema's `contract_version`;
 * `FeatureTaskRuntimeQuarantineSchemaContractVersionTest` fails the build if they diverge.
 * Pins the durable append-only quarantined-record list the launch-seam quarantine-and-regenerate
 * edge writes.
 */
const val FEATURE_TASK_RUNTIME_QUARANTINE_CONTRACT_VERSION: String = "0.1"

object FeatureTaskRuntimeQuarantineSchemaPaths {
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/feature-task-runtime-quarantine-schema.yaml"
  const val CLASSPATH_RESOURCE: String =
    "skillbill/contracts/feature-task-runtime-quarantine-schema.yaml"
  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/feature-task-runtime-quarantine-schema.yaml"
}

const val FEATURE_TASK_RUNTIME_AUDIT_REPAIR_CONTRACT_VERSION: String = "0.2"

object FeatureTaskRuntimeAuditRepairPlanSchemaPaths {
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/feature-task-runtime-audit-repair-plan-schema.yaml"
  const val CLASSPATH_RESOURCE: String =
    "skillbill/contracts/feature-task-runtime-audit-repair-plan-schema.yaml"
  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/feature-task-runtime-audit-repair-plan-schema.yaml"
}

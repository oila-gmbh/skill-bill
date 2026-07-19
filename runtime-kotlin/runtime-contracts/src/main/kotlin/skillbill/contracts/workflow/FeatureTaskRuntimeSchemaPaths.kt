package skillbill.contracts.workflow

/**
 * Runtime-side mirror of the schema's `contract_version`; a parity test fails
 * the build if they diverge. Bump both sites together.
 */
const val FEATURE_TASK_RUNTIME_CONTRACT_VERSION: String = "0.1"

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

const val FEATURE_TASK_RUNTIME_AUDIT_REPAIR_CONTRACT_VERSION: String = "0.2"

object FeatureTaskRuntimeAuditRepairPlanSchemaPaths {
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/feature-task-runtime-audit-repair-plan-schema.yaml"
  const val CLASSPATH_RESOURCE: String =
    "skillbill/contracts/feature-task-runtime-audit-repair-plan-schema.yaml"
  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/feature-task-runtime-audit-repair-plan-schema.yaml"
}

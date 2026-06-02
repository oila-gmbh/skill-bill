package skillbill.contracts.workflow

/**
 * SKILL-64 Subtask 3: pinned runtime-side mirror of the canonical
 * goal declared-progress-event schema's `contract_version`. The parity test
 * fails the build if this constant and the schema's
 * `properties.contract_version.const` diverge.
 */
const val GOAL_PROGRESS_EVENT_CONTRACT_VERSION: String = "0.1"

/**
 * Single source of truth for the canonical goal declared-progress-event schema
 * path. The Gradle copy task in `runtime-infra-fs/build.gradle.kts` mirrors
 * these values because Gradle's Kotlin DSL cannot import runtime constants
 * directly.
 */
object GoalProgressEventSchemaPaths {
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/goal-progress-event-schema.yaml"

  const val CLASSPATH_RESOURCE: String =
    "skillbill/contracts/goal-progress-event-schema.yaml"

  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/goal-progress-event-schema.yaml"
}

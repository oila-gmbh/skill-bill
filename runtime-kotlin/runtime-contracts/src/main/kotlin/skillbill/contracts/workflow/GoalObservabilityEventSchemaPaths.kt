package skillbill.contracts.workflow

/**
 * Pinned runtime-side mirror of the canonical goal-observability event
 * schema's `contract_version`. The parity test fails the build if this
 * constant and the schema's `properties.contract_version.const` diverge.
 */
const val GOAL_OBSERVABILITY_EVENT_CONTRACT_VERSION: String = "0.1"

/**
 * Single source of truth for the canonical goal-observability event
 * schema path. The Gradle copy task in `runtime-infra-fs/build.gradle.kts`
 * mirrors these values because Gradle's Kotlin DSL cannot import runtime
 * constants directly.
 */
object GoalObservabilityEventSchemaPaths {
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/goal-observability-event-schema.yaml"

  const val CLASSPATH_RESOURCE: String =
    "skillbill/contracts/goal-observability-event-schema.yaml"

  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/goal-observability-event-schema.yaml"
}

package skillbill.contracts.workflow

/**
 * SKILL-148 Subtask 1: pinned runtime-side mirror of the canonical IDE status
 * schema's `contract_version`. The parity test fails the build if this constant
 * and the schema's `properties.contract_version.const` diverge.
 */
const val IDE_STATUS_CONTRACT_VERSION: String = "0.2"

/**
 * Upper bound on how many subtask plans one goal planning wave covers at once. The IDE status
 * schema mirrors this as `properties.planning.properties.planning_wave_subtask_ids.maxItems`, and
 * the planning sweep bounds its dispatch by it, so both sides move together.
 */
const val GOAL_PLANNING_WAVE_CAP: Int = 5

/**
 * Single source of truth for the canonical IDE status schema path. The Gradle
 * copy task in `runtime-infra-fs/build.gradle.kts` mirrors these values because
 * Gradle's Kotlin DSL cannot import runtime constants directly.
 */
object IdeStatusSchemaPaths {
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/ide-status-schema.yaml"

  const val CLASSPATH_RESOURCE: String =
    "skillbill/contracts/ide-status-schema.yaml"

  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/ide-status-schema.yaml"
}

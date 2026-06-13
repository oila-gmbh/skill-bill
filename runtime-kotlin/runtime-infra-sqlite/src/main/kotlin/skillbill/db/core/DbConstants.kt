package skillbill.db.core

import java.nio.file.Path

object DbConstants {
  const val DB_ENVIRONMENT_KEY: String = "SKILL_BILL_REVIEW_DB"
  const val FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION: String = "0.1"
  const val FEATURE_VERIFY_WORKFLOW_CONTRACT_VERSION: String = "0.1"

  // Table's own row contract version, not an external YAML schema; the
  // workflow-state schema still governs the artifacts_json envelope.
  const val FEATURE_TASK_RUNTIME_WORKFLOW_CONTRACT_VERSION: String = "0.1"

  val findingOutcomeTypes: Set<String> =
    setOf(
      "finding_accepted",
      "fix_applied",
      "finding_edited",
      "fix_rejected",
      "false_positive",
    )

  fun defaultDbPath(userHome: Path): Path = userHome.resolve(".skill-bill").resolve("review-metrics.db")
}

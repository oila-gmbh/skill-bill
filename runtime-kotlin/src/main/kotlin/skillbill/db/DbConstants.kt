package skillbill.db

import java.nio.file.Path

object DbConstants {
  const val DB_ENVIRONMENT_KEY: String = "SKILL_BILL_REVIEW_DB"
  const val FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION: String = "0.1"
  const val FEATURE_VERIFY_WORKFLOW_CONTRACT_VERSION: String = "0.1"

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

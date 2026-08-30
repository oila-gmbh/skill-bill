package skillbill.infrastructure.sqlite.review

import java.sql.Connection

data class ReviewFinishedPayloadBuildRequest(
  val connection: Connection,
  val reviewRunId: String,
  val reviewSummary: ReviewSummary? = null,
  val findingRows: List<FindingOutcomeRow>? = null,
  val level: String = "anonymous",
  val routedSkillPlatformSlugs: Map<String, String> = emptyMap(),
)

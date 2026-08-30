package skillbill.infrastructure.sqlite.review

import skillbill.review.model.FindingOutcomeRow
import skillbill.review.model.ReviewSummary
import java.sql.Connection

data class ReviewFinishedPayloadBuildRequest(
  val connection: Connection,
  val reviewRunId: String,
  val reviewSummary: ReviewSummary? = null,
  val findingRows: List<FindingOutcomeRow>? = null,
  val level: String = "anonymous",
  val routedSkillPlatformSlugs: Map<String, String> = emptyMap(),
)

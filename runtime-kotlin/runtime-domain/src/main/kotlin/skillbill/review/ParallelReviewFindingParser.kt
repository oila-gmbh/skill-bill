package skillbill.review

import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ParallelReviewSeverity

object ParallelReviewFindingParser {
  val parallelFindingPattern: Regex = Regex(
    "^\\s*(?:-\\s+)?\\[(?<findingId>F-\\d{3})]\\s+" +
      "(?<severity>Blocker|Critical|Major|Minor|Nit)\\s+\\|\\s+" +
      "(?<confidenceLevel>High|Medium|Low)\\s+\\|\\s+" +
      "(?<location>[^|]+?)\\s+\\|\\s+" +
      "(?<description>.+)$",
    RegexOption.MULTILINE,
  )

  fun parse(text: String): List<ParallelReviewRawFinding> = parallelFindingPattern.findAll(text).mapNotNull { match ->
    val severityStr = match.groups["severity"]?.value.orEmpty()
    val severity = mapSeverity(severityStr) ?: return@mapNotNull null
    ParallelReviewRawFinding(
      severity = severity,
      confidence = match.groups["confidenceLevel"]?.value.orEmpty(),
      location = match.groups["location"]?.value.orEmpty().trim(),
      description = match.groups["description"]?.value.orEmpty().trim(),
    )
  }.toList()

  private fun mapSeverity(severityStr: String): ParallelReviewSeverity? = when (severityStr.lowercase()) {
    "blocker", "critical" -> ParallelReviewSeverity.BLOCKER
    "major" -> ParallelReviewSeverity.MAJOR
    "minor" -> ParallelReviewSeverity.MINOR
    "nit" -> ParallelReviewSeverity.NIT
    else -> null
  }
}

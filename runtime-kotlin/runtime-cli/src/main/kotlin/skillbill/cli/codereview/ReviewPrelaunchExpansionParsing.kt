package skillbill.cli.codereview

import com.github.ajalt.clikt.core.UsageError
import skillbill.application.review.model.ReviewPrelaunchExpansion

internal fun parseReviewPrelaunchExpansion(value: String): ReviewPrelaunchExpansion {
  val reasonSeparator = value.indexOf('=')
  val firstSeparator = value.indexOf(':')
  if (firstSeparator <= 0 || reasonSeparator <= firstSeparator + 1 || reasonSeparator == value.lastIndex) {
    throw UsageError("--expand-file must use LANE:PATH=REACHABILITY_REASON with non-blank values.")
  }
  val prefix = value.substring(0, firstSeparator)
  val remainder = value.substring(firstSeparator + 1, reasonSeparator)
  val skill = remainder.substringBefore(':')
  val laneSeparator = if (isPlatformLanePrefix(prefix) &&
    ':' in remainder && skill.matches(Regex("bill-[a-z0-9]+(?:-[a-z0-9]+)*"))
  ) {
    value.indexOf(':', firstSeparator + 1)
  } else {
    firstSeparator
  }
  return ReviewPrelaunchExpansion(
    lane = value.substring(0, laneSeparator),
    path = value.substring(laneSeparator + 1, reasonSeparator),
    reachabilityReason = value.substring(reasonSeparator + 1),
  )
}

private fun isPlatformLanePrefix(prefix: String): Boolean =
  !prefix.startsWith("bill-") && prefix != "parallel-code-review"

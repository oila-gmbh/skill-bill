package skillbill.review

import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.review.model.ReviewSeverityAdjustmentDirection

internal data class ParallelReviewTrailingStructuredFields(
  val description: String = "",
  val claimVerdict: ReviewClaimVerdict? = null,
  val scopeDisposition: ReviewScopeDisposition? = null,
  val citations: List<ReviewFindingCitation> = emptyList(),
  val severityAdjustment: ReviewSeverityAdjustment? = null,
)

internal fun peelTrailingStructuredFields(rawDescription: String): ParallelReviewTrailingStructuredFields {
  val parts = rawDescription.split(" | ").toMutableList()
  var peeled = ParallelReviewTrailingStructuredFields(description = "")
  while (parts.isNotEmpty()) {
    val next = applyTrailingStructuredToken(parts.last(), peeled) ?: break
    parts.removeLast()
    peeled = next
  }
  return peeled.copy(description = parts.joinToString(" | ").trim())
}

internal fun decodeParallelReviewStructuredString(encoded: String): String {
  require(encoded.length >= 2 && encoded.first() == '"' && encoded.last() == '"')
  val body = encoded.substring(1, encoded.length - 1)
  val result = StringBuilder()
  var index = 0
  while (index < body.length) {
    if (body[index] != '\\') {
      result.append(body[index++])
      continue
    }
    require(++index < body.length) { "Malformed structured finding path escape." }
    when (val escaped = body[index++]) {
      '"', '\\', '/' -> result.append(escaped)
      'b' -> result.append('\b')
      'f' -> result.append('\u000c')
      'n' -> result.append('\n')
      'r' -> result.append('\r')
      't' -> result.append('\t')
      'u' -> {
        require(index + 4 <= body.length) { "Malformed Unicode escape in finding path." }
        result.append(body.substring(index, index + 4).toInt(16).toChar())
        index += 4
      }
      else -> error("Unsupported structured finding path escape '$escaped'.")
    }
  }
  return result.toString()
}

private fun applyTrailingStructuredToken(
  token: String,
  current: ParallelReviewTrailingStructuredFields,
): ParallelReviewTrailingStructuredFields? = when {
  token.startsWith("claim_verdict=") -> {
    val parsed = ReviewClaimVerdict.entries.firstOrNull {
      it.wireValue == token.removePrefix("claim_verdict=").trim()
    } ?: return null
    current.copy(claimVerdict = parsed)
  }
  token.startsWith("scope_disposition=") -> {
    val parsed = ReviewScopeDisposition.entries.firstOrNull {
      it.wireValue == token.removePrefix("scope_disposition=").trim()
    } ?: return null
    current.copy(scopeDisposition = parsed)
  }
  token.startsWith("citations=") -> current.copy(
    citations = parseCitationToken(token.removePrefix("citations=")),
  )
  token.startsWith("severity_adjustment=") -> {
    val parsed = parseSeverityAdjustmentToken(token.removePrefix("severity_adjustment=")) ?: return null
    current.copy(severityAdjustment = parsed)
  }
  else -> null
}

private fun parseCitationToken(raw: String): List<ReviewFindingCitation> = raw.split(',').mapNotNull { item ->
  val trimmed = item.trim()
  val colon = trimmed.lastIndexOf(':')
  if (colon <= 0) return@mapNotNull null
  val path = trimmed.substring(0, colon).trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
  val line = trimmed.substring(colon + 1).trim().toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
  runCatching { ReviewFindingCitation(path, line) }.getOrNull()
}

private fun parseSeverityAdjustmentToken(raw: String): ReviewSeverityAdjustment? {
  val separator = raw.indexOf(": ")
  if (separator <= 0) return null
  val direction = ReviewSeverityAdjustmentDirection.entries.firstOrNull {
    it.wireValue == raw.substring(0, separator).trim()
  } ?: return null
  val justification = raw.substring(separator + 2).trim().takeIf(String::isNotBlank) ?: return null
  return ReviewSeverityAdjustment(direction, justification)
}

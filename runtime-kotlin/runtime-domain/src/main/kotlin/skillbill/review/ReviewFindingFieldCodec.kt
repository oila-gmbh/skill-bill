package skillbill.review

import skillbill.contracts.JsonCodec
import skillbill.review.model.RecordedVerdictFields
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingCitationDiagnostic
import skillbill.review.model.ReviewFindingCitationsDecode
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.review.model.ReviewSeverityAdjustmentDirection

object ReviewFindingFieldCodec {
  fun findingRefOf(id: Any?, findingId: Any?, fNumber: Any?): String? = sequenceOf(id, findingId, fNumber)
    .filterIsInstance<String>()
    .map(String::trim)
    .firstOrNull(String::isNotBlank)

  fun recordedFieldsOf(
    claimVerdict: Any?,
    scopeDisposition: Any?,
    citations: Any?,
    severityAdjustment: Any?,
  ): RecordedVerdictFields = RecordedVerdictFields(
    claimVerdict = claimVerdictOf(claimVerdict),
    scopeDisposition = scopeDispositionOf(scopeDisposition),
    citations = decodeCitations(citations).citations,
    severityAdjustment = severityAdjustmentOf(severityAdjustment),
  )

  fun claimVerdictOf(raw: Any?): ReviewClaimVerdict? =
    (raw as? String)?.trim()?.takeIf(String::isNotBlank)?.let(ReviewClaimVerdict::fromWire)

  fun scopeDispositionOf(raw: Any?): ReviewScopeDisposition? =
    (raw as? String)?.trim()?.takeIf(String::isNotBlank)?.let(ReviewScopeDisposition::fromWire)

  fun citationsOf(raw: Any?): List<ReviewFindingCitation> = decodeCitations(raw).citations

  fun decodeCitations(raw: Any?): ReviewFindingCitationsDecode {
    val items = raw as? List<*> ?: return ReviewFindingCitationsDecode(emptyList(), emptyList())
    val citations = mutableListOf<ReviewFindingCitation>()
    val diagnostics = mutableListOf<ReviewFindingCitationDiagnostic>()
    items.forEachIndexed { index, item ->
      val map = JsonCodec.anyToStringAnyMap(item)
        ?: error("Finding citation entry must be an object.")
      val path = (map["path"] as? String)?.trim()?.takeIf(String::isNotBlank)
        ?: error("Finding citation path must be non-blank.")
      when (val line = parseCitationLine(map["line"])) {
        is ParsedCitationLine.Accepted -> citations += ReviewFindingCitation(path, line.value)
        is ParsedCitationLine.Rejected -> diagnostics += ReviewFindingCitationDiagnostic(
          citationIndex = index,
          path = path,
          rawLine = line.rawLine,
          reason = line.reason,
        )
      }
    }
    return ReviewFindingCitationsDecode(citations, diagnostics)
  }

  fun severityAdjustmentOf(raw: Any?): ReviewSeverityAdjustment? {
    val map = JsonCodec.anyToStringAnyMap(raw) ?: return null
    val direction = (map["direction"] as? String)?.trim()?.takeIf(String::isNotBlank)
      ?.let(ReviewSeverityAdjustmentDirection::fromWire)
      ?: return null
    val justification = (map["justification"] as? String)?.trim()?.takeIf(String::isNotBlank) ?: return null
    return ReviewSeverityAdjustment(direction, justification)
  }

  private sealed interface ParsedCitationLine {
    data class Accepted(val value: Int) : ParsedCitationLine
    data class Rejected(val rawLine: String?, val reason: String) : ParsedCitationLine
  }

  private fun parseCitationLine(raw: Any?): ParsedCitationLine = when (raw) {
    null -> ParsedCitationLine.Rejected(rawLine = null, reason = "missing_line")
    is Number -> parseNumericCitationLine(raw)
    is String -> {
      val trimmed = raw.trim()
      val parsed = trimmed.toIntOrNull()
      when {
        parsed == null -> ParsedCitationLine.Rejected(rawLine = trimmed, reason = "non_numeric_line")
        parsed == 0 -> ParsedCitationLine.Accepted(1)
        parsed < 1 -> ParsedCitationLine.Rejected(rawLine = trimmed, reason = "non_positive_line")
        else -> ParsedCitationLine.Accepted(parsed)
      }
    }
    else -> ParsedCitationLine.Rejected(rawLine = raw.toString(), reason = "non_numeric_line")
  }

  private fun parseNumericCitationLine(raw: Number): ParsedCitationLine {
    val value = raw.toDouble()
    if (!value.isFinite() || value % 1.0 != 0.0 || value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
      return ParsedCitationLine.Rejected(rawLine = raw.toString(), reason = "non_numeric_line")
    }
    val integer = value.toInt()
    return when {
      integer == 0 -> ParsedCitationLine.Accepted(1)
      integer >= 1 -> ParsedCitationLine.Accepted(integer)
      else -> ParsedCitationLine.Rejected(rawLine = raw.toString(), reason = "non_positive_line")
    }
  }
}

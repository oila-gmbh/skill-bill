@file:Suppress("MagicNumber")

package skillbill.review

import skillbill.review.context.model.requireRepositoryRelativePath
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.review.model.ReviewSeverityAdjustmentDirection

object ParallelReviewFindingParser {
  val parallelFindingPattern: Regex = Regex(
    "^\\s*(?:-\\s+)?\\[(?<findingId>F-\\d{3})]\\s+" +
      "(?<severity>Blocker|Critical|Major|Minor|Nit)\\s+\\|\\s+" +
      "(?<confidenceLevel>High|Medium|Low)\\s+\\|\\s+" +
      "(?:specialist=(?<specialistSkillName>[a-z0-9-]+)\\s+\\|\\s+)?" +
      "(?:commits=(?<commits>[^|\\r\\n]+?)\\s+\\|\\s+)?" +
      "(?:path=(?<path>\"(?:\\\\.|[^\"\\\\])*\")\\s+\\|\\s+line=(?<line>\\d+)" +
      "|(?<legacyPath>[^|\\r\\n]+?):(?<legacyLine>\\d+))\\s+\\|\\s+" +
      "(?<description>.+)$",
    RegexOption.MULTILINE,
  )

  fun parse(text: String): List<ParallelReviewRawFinding> = parallelFindingPattern.findAll(text).mapNotNull { match ->
    val severityStr = match.groups["severity"]?.value.orEmpty()
    val severity = mapSeverity(severityStr) ?: return@mapNotNull null
    val structuredPath = match.groups["path"]?.value
    val path = structuredPath?.let(::decodeStructuredString)
      ?: match.groups["legacyPath"]?.value?.trim().orEmpty()
    requireRepositoryRelativePath(path)
    val lineText = match.groups["line"]?.value ?: match.groups["legacyLine"]?.value
    val line = lineText?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
    val peeled = peelTrailingStructuredFields(match.groups["description"]?.value.orEmpty().trim())
    ParallelReviewRawFinding(
      severity = severity,
      confidence = match.groups["confidenceLevel"]?.value.orEmpty(),
      location = "$path:$line",
      description = peeled.description,
      specialistSkillName = match.groups["specialistSkillName"]?.value,
      repositoryPath = path,
      line = line,
      commitShas = parseCommitShas(match.groups["commits"]?.value),
      claimVerdict = peeled.claimVerdict,
      scopeDisposition = peeled.scopeDisposition,
      citations = peeled.citations,
      severityAdjustment = peeled.severityAdjustment,
    )
  }.toList()

  private fun parseCommitShas(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
  }

  private fun peelTrailingStructuredFields(rawDescription: String): TrailingStructuredFields {
    val parts = rawDescription.split(" | ").toMutableList()
    var peeled = TrailingStructuredFields(description = "")
    while (parts.isNotEmpty()) {
      val next = applyTrailingStructuredToken(parts.last(), peeled) ?: break
      parts.removeLast()
      peeled = next
    }
    return peeled.copy(description = parts.joinToString(" | ").trim())
  }

  private fun applyTrailingStructuredToken(
    token: String,
    current: TrailingStructuredFields,
  ): TrailingStructuredFields? = when {
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

  private fun parseCitationToken(raw: String): List<ReviewFindingCitation> =
    raw.split(',').mapNotNull { item ->
      val trimmed = item.trim()
      val colon = trimmed.lastIndexOf(':')
      if (colon <= 0) return@mapNotNull null
      val path = trimmed.substring(0, colon).trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
      val line = trimmed.substring(colon + 1).trim().toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
      ReviewFindingCitation(path, line)
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

  private data class TrailingStructuredFields(
    val description: String = "",
    val claimVerdict: ReviewClaimVerdict? = null,
    val scopeDisposition: ReviewScopeDisposition? = null,
    val citations: List<ReviewFindingCitation> = emptyList(),
    val severityAdjustment: ReviewSeverityAdjustment? = null,
  )

  private fun decodeStructuredString(encoded: String): String {
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

  private fun mapSeverity(severityStr: String): ParallelReviewSeverity? = when (severityStr.lowercase()) {
    "blocker", "critical" -> ParallelReviewSeverity.BLOCKER
    "major" -> ParallelReviewSeverity.MAJOR
    "minor" -> ParallelReviewSeverity.MINOR
    "nit" -> ParallelReviewSeverity.NIT
    else -> null
  }
}

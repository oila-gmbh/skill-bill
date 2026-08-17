@file:Suppress("MagicNumber")

package skillbill.review

import skillbill.review.context.model.requireRepositoryRelativePath
import skillbill.review.model.ParallelReviewFindingRejection
import skillbill.review.model.ParallelReviewFindingRejectionReason
import skillbill.review.model.ParallelReviewParseResult
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
      "(?<severity>[A-Za-z]+)\\s+\\|\\s+" +
      "(?<confidenceLevel>High|Medium|Low)\\s+\\|\\s+" +
      "(?:specialist=(?<specialistSkillName>[a-z0-9-]+)\\s+\\|\\s+)?" +
      "(?:commits=(?<commits>[^|\\r\\n]+?)\\s+\\|\\s+)?" +
      "(?:path=(?<path>\"(?:\\\\.|[^\"\\\\])*\")\\s+\\|\\s+line=(?<line>\\d+)" +
      "|(?<legacyPath>[^|\\r\\n]+?):(?<legacyLine>\\d+))\\s+\\|\\s+" +
      "(?<description>.+)$",
    RegexOption.MULTILINE,
  )

  const val UNASSIGNED_REPOSITORY_PATH = "unassigned"

  private val candidatePattern = Regex("\\[F-\\d+]")

  fun parse(text: String): ParallelReviewParseResult {
    val lines = text.lines()
    val admitted = mutableListOf<ParallelReviewRawFinding>()
    val rejections = mutableListOf<ParallelReviewFindingRejection>()
    val matchedPositions = mutableSetOf<Int>()
    parallelFindingPattern.findAll(text).forEach { match ->
      val position = text.take(match.range.first).count { it == '\n' } + 1
      matchedPositions += position
      val outcome = parseMatch(match)
      outcome.finding?.let { admitted += it }
      outcome.reason?.let { reason ->
        rejections += ParallelReviewFindingRejection(
          lineText = lines.getOrElse(position - 1) { match.value }.trim(),
          linePosition = position,
          reason = reason,
        )
      }
    }
    var candidateCount = 0
    lines.forEachIndexed { index, line ->
      if (!candidatePattern.containsMatchIn(line)) return@forEachIndexed
      candidateCount++
      if (index + 1 in matchedPositions) return@forEachIndexed
      rejections += ParallelReviewFindingRejection(
        lineText = line.trim(),
        linePosition = index + 1,
        reason = ParallelReviewFindingRejectionReason.UNMATCHED_CANDIDATE_LINE,
      )
    }
    return ParallelReviewParseResult(
      findings = admitted,
      rejections = rejections.sortedBy(ParallelReviewFindingRejection::linePosition),
      candidateCount = candidateCount,
    )
  }

  private data class MatchOutcome(
    val finding: ParallelReviewRawFinding? = null,
    val reason: ParallelReviewFindingRejectionReason? = null,
  )

  private data class ResolvedPath(
    val path: String,
    val reason: ParallelReviewFindingRejectionReason? = null,
  )

  private fun parseMatch(match: MatchResult): MatchOutcome {
    val severityStr = match.groups["severity"]?.value.orEmpty()
    val severity = mapSeverity(severityStr)
      ?: return MatchOutcome(reason = ParallelReviewFindingRejectionReason.UNRECOGNIZED_SEVERITY)
    val resolvedPath = resolvePath(match)
    val path = resolvedPath.path
    val lineText = match.groups["line"]?.value ?: match.groups["legacyLine"]?.value
    val line = lineText?.toIntOrNull()?.takeIf { it > 0 }
      ?: return MatchOutcome(reason = ParallelReviewFindingRejectionReason.INVALID_LINE_NUMBER)
    val peeled = peelTrailingStructuredFields(match.groups["description"]?.value.orEmpty().trim())
    return MatchOutcome(
      reason = resolvedPath.reason,
      finding = ParallelReviewRawFinding(
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
      ),
    )
  }

  private fun resolvePath(match: MatchResult): ResolvedPath {
    val structuredPath = match.groups["path"]?.value
    val decoded = if (structuredPath == null) {
      match.groups["legacyPath"]?.value?.trim().orEmpty()
    } else {
      try {
        decodeStructuredString(structuredPath)
      } catch (_: IllegalArgumentException) {
        return ResolvedPath(
          UNASSIGNED_REPOSITORY_PATH,
          ParallelReviewFindingRejectionReason.UNPARSEABLE_STRUCTURED_PATH,
        )
      } catch (_: IllegalStateException) {
        return ResolvedPath(
          UNASSIGNED_REPOSITORY_PATH,
          ParallelReviewFindingRejectionReason.UNPARSEABLE_STRUCTURED_PATH,
        )
      }
    }
    if (decoded.isNotEmpty()) {
      try {
        requireRepositoryRelativePath(decoded)
        return ResolvedPath(decoded)
      } catch (_: IllegalArgumentException) {
      }
    }
    return ResolvedPath(UNASSIGNED_REPOSITORY_PATH, ParallelReviewFindingRejectionReason.NO_ADMISSIBLE_LOCATION)
  }

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

  private fun parseCitationToken(raw: String): List<ReviewFindingCitation> = raw.split(',').mapNotNull { item ->
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

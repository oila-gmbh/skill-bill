@file:Suppress("MagicNumber")

package skillbill.review

import skillbill.review.context.model.requireRepositoryRelativePath
import skillbill.review.model.ParallelReviewFindingRejection
import skillbill.review.model.ParallelReviewFindingRejectionReason
import skillbill.review.model.ParallelReviewParseResult
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ParallelReviewSeverity

object ParallelReviewFindingParser {
  val parallelFindingPattern: Regex = Regex(
    "^\\s*(?:-\\s+)?\\[(?<findingId>F-\\d{3})]\\s+" +
      "(?<severity>[A-Za-z]+)\\s+\\|\\s+" +
      "(?<confidenceLevel>High|Medium|Low)\\s+\\|\\s+" +
      "(?:specialist=(?<specialistSkillName>[a-z0-9-]+)(?:\\[[^\\]]*\\])?\\s+\\|\\s+)?" +
      "(?:commits=(?<commits>[^|\\r\\n]+?)\\s+\\|\\s+)?" +
      "(?:path=(?:(?<pathQuoted>\"(?:\\\\.|[^\"\\\\])*\")|(?<pathBare>[^|\\r\\n]+?))\\s+\\|\\s+" +
      "line=(?<line>\\d+)" +
      "|(?<legacyPath>[^|\\r\\n]+?):(?<legacyLine>\\d+))\\s+\\|\\s+" +
      "(?<description>.+)$",
    RegexOption.MULTILINE,
  )

  const val UNASSIGNED_REPOSITORY_PATH = "unassigned"

  val findingCandidatePattern: Regex = Regex("\\[F-\\d+]")

  private val findingNumberPattern: Regex = Regex("\\[F-(\\d+)]")

  private val nearMissFindingIdLine: Regex = Regex(
    """^(\s*(?:-\s+)?)(?:\|\s*)?(?:\*{1,2}|_{1,2})?\[F-(\d+)](?:\*{1,2}|_{1,2})?(?:\s*\|)?\s+(.*)$""",
  )

  private val findingBodyWithoutId: Regex = Regex(
    """^(\s*(?:-\s+)?)(?!(?:\|\s*)?(?:\*{1,2}|_{1,2})?\[F-)""" +
      """(?:Major|Minor|Blocker|Nit|Critical)\s+\|\s+(?:High|Medium|Low)\s+\|\s+\S.*$""",
  )

  fun countRegisterCandidates(text: String): Int =
    text.lineSequence().count { findingCandidatePattern.containsMatchIn(it) }

  fun parse(text: String): ParallelReviewParseResult {
    val normalizedText = normalizeRegisterText(text)
    val lines = normalizedText.lines()
    val admitted = mutableListOf<ParallelReviewRawFinding>()
    val rejections = mutableListOf<ParallelReviewFindingRejection>()
    val matchedPositions = mutableSetOf<Int>()
    parallelFindingPattern.findAll(normalizedText).forEach { match ->
      val position = linePositionOfFindingToken(normalizedText, match)
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
      if (!findingCandidatePattern.containsMatchIn(line)) return@forEachIndexed
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

  internal fun normalizeRegisterText(text: String): String {
    val working = text.lines().map { normalizeRegisterLine(it) }.toMutableList()
    var next = working.maxOfOrNull(::maxFindingNumberIn) ?: 0
    working.indices.forEach { index ->
      val line = working[index]
      if (!findingCandidatePattern.containsMatchIn(line)) {
        findingBodyWithoutId.matchEntire(line)?.let { body ->
          next = (next + 1).coerceAtMost(999)
          val id = "F-" + next.toString().padStart(3, '0')
          working[index] = "${body.groupValues[1]}[$id] ${line.drop(body.groupValues[1].length)}"
        }
      }
    }
    return working.joinToString("\n")
  }

  internal fun normalizeRegisterLine(line: String): String {
    val match = nearMissFindingIdLine.matchEntire(line) ?: return line
    val number = match.groupValues[2].toIntOrNull() ?: return line
    val paddedId = "F-" + number.coerceIn(0, 999).toString().padStart(3, '0')
    return "${match.groupValues[1]}[$paddedId] ${match.groupValues[3]}"
  }

  private fun maxFindingNumberIn(line: String): Int =
    findingNumberPattern.findAll(line).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0

  private fun linePositionOfFindingToken(text: String, match: MatchResult): Int {
    val tokenAt = match.value.indexOf('[').takeIf { it >= 0 } ?: 0
    return text.take(match.range.first + tokenAt).count { it == '\n' } + 1
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
    resolvedPath.reason?.let { return MatchOutcome(reason = it) }
    val path = resolvedPath.path
    val lineText = match.groups["line"]?.value ?: match.groups["legacyLine"]?.value
    val line = lineText?.toIntOrNull()?.takeIf { it > 0 }
      ?: return MatchOutcome(reason = ParallelReviewFindingRejectionReason.INVALID_LINE_NUMBER)
    val peeled = peelTrailingStructuredFields(match.groups["description"]?.value.orEmpty().trim())
    return MatchOutcome(
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
    val quoted = match.groups["pathQuoted"]?.value
    val bare = match.groups["pathBare"]?.value?.trim()
    val decoded = when {
      quoted != null -> try {
        decodeParallelReviewStructuredString(quoted)
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
      bare != null -> bare
      else -> match.groups["legacyPath"]?.value?.trim().orEmpty()
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

  private fun mapSeverity(severityStr: String): ParallelReviewSeverity? = when (severityStr.lowercase()) {
    "blocker", "critical" -> ParallelReviewSeverity.BLOCKER
    "major" -> ParallelReviewSeverity.MAJOR
    "minor" -> ParallelReviewSeverity.MINOR
    "nit" -> ParallelReviewSeverity.NIT
    else -> null
  }
}

@file:Suppress("MagicNumber")

package skillbill.review

import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.context.model.requireRepositoryRelativePath

object ParallelReviewFindingParser {
  val parallelFindingPattern: Regex = Regex(
    "^\\s*(?:-\\s+)?\\[(?<findingId>F-\\d{3})]\\s+" +
      "(?<severity>Blocker|Critical|Major|Minor|Nit)\\s+\\|\\s+" +
      "(?<confidenceLevel>High|Medium|Low)\\s+\\|\\s+" +
      "(?:specialist=(?<specialistSkillName>[a-z0-9-]+)\\s+\\|\\s+)?" +
      "path=(?<path>\"(?:\\\\.|[^\"\\\\])*\")\\s+\\|\\s+line=(?<line>\\d+)\\s+\\|\\s+" +
      "(?<description>.+)$",
    RegexOption.MULTILINE,
  )

  fun parse(text: String): List<ParallelReviewRawFinding> = parallelFindingPattern.findAll(text).mapNotNull { match ->
    val severityStr = match.groups["severity"]?.value.orEmpty()
    val severity = mapSeverity(severityStr) ?: return@mapNotNull null
    val path = decodeStructuredString(match.groups["path"]?.value.orEmpty())
    requireRepositoryRelativePath(path)
    val line = match.groups["line"]?.value?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
    ParallelReviewRawFinding(
      severity = severity,
      confidence = match.groups["confidenceLevel"]?.value.orEmpty(),
      location = "$path:$line",
      description = match.groups["description"]?.value.orEmpty().trim(),
      specialistSkillName = match.groups["specialistSkillName"]?.value,
      repositoryPath = path,
      line = line,
    )
  }.toList()

  private fun decodeStructuredString(encoded: String): String {
    require(encoded.length >= 2 && encoded.first() == '"' && encoded.last() == '"')
    val body = encoded.substring(1, encoded.length - 1)
    val result = StringBuilder()
    var index = 0
    while (index < body.length) {
      if (body[index] != '\\') { result.append(body[index++]); continue }
      require(++index < body.length) { "Malformed structured finding path escape." }
      when (val escaped = body[index++]) {
        '"', '\\', '/' -> result.append(escaped)
        'b' -> result.append('\b'); 'f' -> result.append('\u000c'); 'n' -> result.append('\n')
        'r' -> result.append('\r'); 't' -> result.append('\t')
        'u' -> {
          require(index + 4 <= body.length) { "Malformed Unicode escape in finding path." }
          result.append(body.substring(index, index + 4).toInt(16).toChar()); index += 4
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

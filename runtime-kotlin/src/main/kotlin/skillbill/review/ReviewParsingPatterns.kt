package skillbill.review

import java.nio.file.Path

val reviewRunIdPattern =
  Regex(
    "^Review run ID:\\s*(?<value>[A-Za-z0-9._:-]+)\\s*$",
    RegexOption.MULTILINE,
  )
val reviewSessionIdPattern =
  Regex(
    "^Review session ID:\\s*(?<value>[A-Za-z0-9._:-]+)\\s*$",
    RegexOption.MULTILINE,
  )
val summaryPatterns: Map<String, Regex> =
  mapOf(
    "routed_skill" to Regex("^Routed to:\\s*(?<value>.+?)\\s*$", RegexOption.MULTILINE),
    "detected_scope" to Regex("^Detected review scope:\\s*(?<value>.+?)\\s*$", RegexOption.MULTILINE),
    "detected_stack" to Regex("^Detected stack:\\s*(?<value>.+?)\\s*$", RegexOption.MULTILINE),
    "execution_mode" to Regex("^Execution mode:\\s*(?<value>inline|delegated)\\s*$", RegexOption.MULTILINE),
  )
val specialistReviewsPattern =
  Regex(
    "^(?:Specialist reviews|Baseline review|Backend specialist reviews|KMP specialist reviews):\\s*(?<value>.+?)\\s*$",
    RegexOption.MULTILINE,
  )
val findingPattern =
  Regex(
    "^\\s*-\\s+\\[(?<findingId>F-\\d{3})]\\s+" +
      "(?<severity>Blocker|Major|Minor)\\s+\\|\\s+" +
      "(?<confidenceLevel>High|Medium|Low)\\s+\\|\\s+" +
      "(?<location>[^|]+?)\\s+\\|\\s+" +
      "(?<description>.+)$",
    RegexOption.MULTILINE,
  )
val severityAliases: Map<String, String> =
  mapOf(
    "high" to "Major",
    "medium" to "Minor",
    "low" to "Minor",
    "p1" to "Blocker",
    "p2" to "Major",
    "p3" to "Minor",
    "critical" to "Blocker",
    "blocker" to "Blocker",
    "major" to "Major",
    "minor" to "Minor",
    "info" to "Minor",
  )

fun expandAndNormalizePath(rawPath: String): Path {
  val userHome = Path.of(System.getProperty("user.home"))
  val normalized =
    when {
      rawPath == "~" -> userHome.toString()
      rawPath.startsWith("~/") -> userHome.resolve(rawPath.removePrefix("~/")).toString()
      else -> rawPath
    }
  return Path.of(normalized).toAbsolutePath().normalize()
}

package skillbill.application.goalrunner

import skillbill.contracts.JsonSupport
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding

internal object GoalSubtaskReviewSummaryReducer {
  private const val MAX_TEXT_LENGTH: Int = 180
  private val pathLikeToken = Regex("(?:[A-Za-z]:)?(?:[/\\\\][^\\s:|]+)+|(?:[A-Za-z0-9_.-]+[/\\\\])+[A-Za-z0-9_.-]+")
  private val lineSuffix = Regex("(?::\\d+(?::\\d+)?)|(?:\\bline\\s+\\d+\\b)", RegexOption.IGNORE_CASE)
  private val hunk = Regex("@@[^@]+@@")
  private val classOrSymbol = Regex("\\b[A-Z][A-Za-z0-9_]*(?:[.#][A-Za-z_][A-Za-z0-9_]*)?\\b")
  private val fileStem = Regex("(?:^|[/\\\\])([A-Za-z0-9_.-]+)\\.[A-Za-z0-9]+(?::\\d+)?")
  private val bareFilenameToken = Regex("\\b[A-Za-z0-9][A-Za-z0-9_.-]*\\.[A-Za-z0-9]+\\b")

  fun fromOutput(output: Map<String, Any?>): List<GoalSubtaskReviewCompactFinding> {
    val findings = output["produced_outputs"]
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.get("findings") as? List<*>
      ?: return emptyList()
    return findings.mapNotNull { entry ->
      val finding = JsonSupport.anyToStringAnyMap(entry) ?: return@mapNotNull null
      val severity = (finding["severity"] as? String)?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        ?: return@mapNotNull null
      val message = (finding["message"] as? String)?.trim()?.takeIf(String::isNotBlank)
        ?: return@mapNotNull null
      GoalSubtaskReviewCompactFinding(
        severity = severity,
        label = labelFor(finding, message),
        text = sanitize(message),
      )
    }.groupBy { finding -> finding.label.lowercase() }
      .values
      .map { sameLabelFindings ->
        sameLabelFindings.minByOrNull(::severityRank)
          ?: error("A grouped compact review summary must contain at least one finding.")
      }
  }

  fun unresolvedCount(output: Map<String, Any?>): Int = fromOutput(output)
    .count { finding -> finding.severity == "blocker" || finding.severity == "major" }

  private fun labelFor(finding: Map<String, Any?>, message: String): String {
    explicitLabel(finding)?.let { return it }
    val symbol = classOrSymbol.find(message)?.value
    if (symbol != null) {
      return symbol.removeSuffix(".kt").removeSuffix(".java").removeSuffix(".kts")
    }
    return fileStem.find(message)?.groupValues?.get(1)?.substringBeforeLast('.')?.takeIf(String::isNotBlank)
      ?: "Review"
  }

  private fun explicitLabel(finding: Map<String, Any?>): String? = sequenceOf(
    finding["class_or_symbol"],
    finding["symbol"],
    finding["class"],
  ).filterIsInstance<String>()
    .map(String::trim)
    .map(::compactLabel)
    .firstOrNull(String::isNotBlank)

  private fun compactLabel(value: String): String {
    classOrSymbol.find(value)?.value?.let { symbol ->
      return symbol.removeSuffix(".kt").removeSuffix(".java").removeSuffix(".kts")
    }
    val fileStemLabel = fileStem.find(value)?.groupValues?.get(1)?.substringBeforeLast('.')?.takeIf(String::isNotBlank)
    if (fileStemLabel != null) return fileStemLabel
    return value.takeUnless {
      pathLikeToken.containsMatchIn(it) || lineSuffix.containsMatchIn(it) || bareFilenameToken.containsMatchIn(it)
    }.orEmpty()
  }

  private fun severityRank(finding: GoalSubtaskReviewCompactFinding): Int = when (finding.severity) {
    "blocker" -> 0
    "major" -> 1
    "minor" -> 2
    else -> 3
  }

  private fun sanitize(message: String): String = message
    .replace(hunk, " ")
    .replace(pathLikeToken, " ")
    .replace(bareFilenameToken, " ")
    .replace(lineSuffix, " ")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(MAX_TEXT_LENGTH)
    .ifBlank { "Review finding" }
}

package skillbill.application.goalrunner

import skillbill.contracts.JsonSupport
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding

internal data class GoalSubtaskReviewOutputOutcome(
  val verdict: FeatureTaskRuntimeVerdict,
  val unresolvedFindingCount: Int,
)

internal object GoalSubtaskReviewSummaryReducer {
  private const val MAX_TEXT_LENGTH: Int = 180
  private val pathLikeToken = Regex("(?:[A-Za-z]:)?(?:[/\\\\][^\\s:|]+)+|(?:[A-Za-z0-9_.-]+[/\\\\])+[A-Za-z0-9_.-]+")
  private val hunk = Regex("@@[^@]+@@")
  private val lineLocation = Regex(
    "(?:\\b(?:lines?|ln)\\s*:?\\s*\\d+(?:\\s*[-–]\\s*\\d+)?)|" +
      "(?:\\b(?:L|#)\\s*\\d+(?:\\s*[-–]\\s*(?:L|#)?\\s*\\d+)?)|" +
      "(?:\\b(?:columns?|cols?)\\s*:?\\s*\\d+(?:\\s*[-–]\\s*\\d+)?)|" +
      "(?::\\s*\\d+(?::\\s*\\d+)?(?:\\s*[-–]\\s*\\d+)?)|" +
      "(?:[\\(\\[\\{]\\s*\\d+(?:\\s*,\\s*\\d+)?\\s*[\\)\\]\\}])",
    RegexOption.IGNORE_CASE,
  )
  private val classOrSymbol = Regex("^[A-Z][A-Za-z0-9_]*(?:[.#][A-Za-z_][A-Za-z0-9_]*)?$")
  private val fileStem = Regex("(?:^|[/\\\\])([A-Za-z0-9_.-]+)\\.[A-Za-z0-9]+(?::\\d+(?:-\\d+)?)?")
  private val bareFilenameToken = Regex("\\b[A-Za-z0-9][A-Za-z0-9_.-]*\\.[A-Za-z0-9]+\\b")
  private val diffFragment = Regex("(?i)(?:\\bdiff\\s+--git\\b|\\bindex\\s+[0-9a-f]{7,}\\b|---|\\+\\+\\+)")

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

  fun outcomeFor(
    output: Map<String, Any?>,
    findings: List<GoalSubtaskReviewCompactFinding> = fromOutput(output),
  ): GoalSubtaskReviewOutputOutcome {
    val structuredUnresolved = findings.count { finding ->
      finding.severity == "blocker" || finding.severity == "major"
    }
    val declaredVerdict = (output["verdict"] as? String)?.trim()
    val changesRequested = declaredVerdict in setOf("needs_fix", FeatureTaskRuntimeVerdict.CHANGES_REQUESTED.wireValue)
    val verdict = when {
      structuredUnresolved > 0 || changesRequested -> FeatureTaskRuntimeVerdict.CHANGES_REQUESTED
      declaredVerdict?.isNotBlank() == true -> FeatureTaskRuntimeVerdict.fromWire(declaredVerdict)
      else -> FeatureTaskRuntimeVerdict.APPROVED
    }
    return GoalSubtaskReviewOutputOutcome(
      verdict = verdict,
      unresolvedFindingCount = if (verdict == FeatureTaskRuntimeVerdict.APPROVED) {
        structuredUnresolved
      } else {
        structuredUnresolved.coerceAtLeast(1)
      },
    )
  }

  private fun labelFor(finding: Map<String, Any?>, message: String): String {
    explicitLabel(finding)?.let { return it }
    return fileStem.find(message)?.groupValues?.get(1)?.substringBeforeLast('.')?.takeIf(String::isNotBlank)
      ?: "Review"
  }

  private fun explicitLabel(finding: Map<String, Any?>): String? = sequenceOf(
    finding["class_or_symbol"],
    finding["symbol"],
    finding["class"],
  ).filterIsInstance<String>()
    .map(String::trim)
    .filter(classOrSymbol::matches)
    .firstOrNull(String::isNotBlank)

  private fun severityRank(finding: GoalSubtaskReviewCompactFinding): Int =
    CompactFindingSeverity.from(finding.severity).ordinal

  private fun sanitize(message: String): String {
    val compact = message
      .replace(hunk, " ")
      .replace(pathLikeToken, " ")
      .replace(bareFilenameToken, " ")
      .replace(lineLocation, " ")
      .replace(diffFragment, " ")
      .replace(Regex("\\s+"), " ")
      .trim()
      .take(MAX_TEXT_LENGTH)
    return if (compact.isBlank() || containsUnsafeReviewMaterial(compact)) "Review finding" else compact
  }

  private fun containsUnsafeReviewMaterial(value: String): Boolean = pathLikeToken.containsMatchIn(value) ||
    bareFilenameToken.containsMatchIn(value) ||
    hunk.containsMatchIn(value) ||
    lineLocation.containsMatchIn(value) ||
    diffFragment.containsMatchIn(value)
}

private enum class CompactFindingSeverity {
  BLOCKER,
  MAJOR,
  MINOR,
  OTHER,
  ;

  companion object {
    fun from(value: String): CompactFindingSeverity = when (value) {
      "blocker" -> BLOCKER
      "major" -> MAJOR
      "minor" -> MINOR
      else -> OTHER
    }
  }
}

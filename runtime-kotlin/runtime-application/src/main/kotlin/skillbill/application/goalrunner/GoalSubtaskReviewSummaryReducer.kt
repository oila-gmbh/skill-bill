package skillbill.application.goalrunner

import skillbill.contracts.JsonSupport
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.goalrunner.model.normalizedUnaddressedFindingCategory
import skillbill.goalrunner.model.normalizedUnaddressedFindingSeverity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_PASS_VERDICTS
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDispositionVerdict
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.reviewStateError

internal data class StructuredGoalReviewFinding(
  val severity: String,
  val message: String,
  val issueCategory: String,
  val location: String,
  val compactLabel: String,
  val findingId: String? = null,
)

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
    return structuredFindings(output).map { finding ->
      GoalSubtaskReviewCompactFinding(
        severity = finding.severity,
        label = finding.compactLabel,
        text = sanitize(finding.message),
        findingId = finding.findingId,
      )
    }.groupBy { finding -> finding.label.lowercase() }
      .values
      .map { sameLabelFindings ->
        sameLabelFindings.minByOrNull(::severityRank)
          ?: error("A grouped compact review summary must contain at least one finding.")
      }
  }

  fun structuredFindings(output: Map<String, Any?>): List<StructuredGoalReviewFinding> {
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
      StructuredGoalReviewFinding(
        severity = severity,
        message = message,
        issueCategory = sequenceOf(finding["issue_category"], finding["category"])
          .filterIsInstance<String>().firstOrNull()?.trim()?.lowercase() ?: "other",
        location = sequenceOf(finding["location"], finding["artifact_ref"])
          .filterIsInstance<String>().firstOrNull()?.trim()?.takeIf(String::isNotBlank) ?: "<unknown>",
        compactLabel = labelFor(finding, message),
        findingId = (finding["id"] as? String)?.trim()?.takeIf(String::isNotBlank),
      )
    }
  }

  fun unaddressedFindings(
    output: Map<String, Any?>,
    issueKey: String,
    subtaskId: Int,
    workflowId: String,
    reviewPassNumber: Int,
  ): List<UnaddressedFinding> = structuredFindings(output).mapIndexed { index, finding ->
    UnaddressedFinding(
      issueKey = issueKey,
      subtaskId = subtaskId,
      workflowId = workflowId,
      reviewPassNumber = reviewPassNumber,
      findingOrdinal = index + 1,
      severity = normalizedUnaddressedFindingSeverity(finding.severity),
      issueCategory = normalizedUnaddressedFindingCategory(finding.issueCategory),
      location = finding.location,
      summary = finding.message,
    )
  }

  /**
   * Parse seam for the reserved remediation pass's per-Blocker dispositions. An entry without
   * location-bearing evidence is rejected here rather than persisted unevidenced, and — when the
   * prior pass's Blocker ids are known — emitted ids are cross-checked against them. Dispositions
   * are optional because review findings are durable advisory records and cannot block advancement.
   */
  fun blockerDispositions(
    output: Map<String, Any?>,
    priorBlockerFindingIds: List<String> = emptyList(),
  ): List<GoalSubtaskBlockerDisposition> {
    val dispositions = output["produced_outputs"]
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.get("blocker_dispositions")
      ?.let { it as? List<*> }
      ?.mapIndexed(::blockerDisposition)
      .orEmpty()
    if (priorBlockerFindingIds.isEmpty()) return dispositions
    val expected = priorBlockerFindingIds.toSet()
    val emitted = dispositions.map(GoalSubtaskBlockerDisposition::findingId).toSet()
    (emitted - expected).sorted().takeIf { it.isNotEmpty() }?.let { unknown ->
      reviewStateError(
        "produced_outputs.blocker_dispositions",
        "dispositions ${unknown.joinToString()} do not correspond to any Blocker the prior pass " +
          "emitted (expected ${expected.sorted().joinToString()}).",
      )
    }
    return dispositions
  }

  fun unresolvedCount(output: Map<String, Any?>): Int = fromOutput(output)
    .count { finding -> finding.severity == "blocker" }

  fun outcomeFor(
    output: Map<String, Any?>,
    findings: List<GoalSubtaskReviewCompactFinding> = fromOutput(output),
  ): GoalSubtaskReviewOutputOutcome {
    // Structured findings of every severity are durable advisory records. They remain available in
    // the findings ledger, but no individual review finding can reopen implementation or halt the
    // workflow.
    val hasStructuredFindings = findings.isNotEmpty()
    val declaredVerdict = (output["verdict"] as? String)?.trim()
    val changesRequested = declaredVerdict in setOf("needs_fix", FeatureTaskRuntimeVerdict.CHANGES_REQUESTED.wireValue)
    val verdict = when {
      hasStructuredFindings -> FeatureTaskRuntimeVerdict.APPROVED
      changesRequested -> FeatureTaskRuntimeVerdict.CHANGES_REQUESTED
      // The pass vocabulary is closed, so an off-vocabulary verdict cannot be persisted as emitted.
      // It resolves the way the transition function already resolves it — only changes_requested
      // remediates, everything else advances — so the durable pass cannot disagree with the loop that
      // read the same output.
      declaredVerdict?.isNotBlank() == true -> FeatureTaskRuntimeVerdict.fromWire(declaredVerdict)
        .takeIf(GOAL_SUBTASK_REVIEW_PASS_VERDICTS::contains)
        ?: FeatureTaskRuntimeVerdict.APPROVED
      else -> FeatureTaskRuntimeVerdict.APPROVED
    }
    return GoalSubtaskReviewOutputOutcome(
      verdict = verdict,
      unresolvedFindingCount = when {
        hasStructuredFindings ||
          verdict == FeatureTaskRuntimeVerdict.APPROVED ||
          verdict == FeatureTaskRuntimeVerdict.REVIEW_SKIPPED_BY_USER -> 0
        else -> 1
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

private fun severityRank(finding: GoalSubtaskReviewCompactFinding): Int =
  CompactFindingSeverity.from(finding.severity).ordinal

private fun blockerDisposition(index: Int, entry: Any?): GoalSubtaskBlockerDisposition {
  val path = "produced_outputs.blocker_dispositions[$index]"
  val disposition = JsonSupport.anyToStringAnyMap(entry)
    ?: reviewStateError(path, "must be an object.")
  val evidence = (disposition["evidence"] as? List<*>)
    ?.mapNotNull { it as? String }
    ?.map(String::trim)
    ?.filter(String::isNotBlank)
    .orEmpty()
  if (evidence.isEmpty()) {
    reviewStateError("$path.evidence", "must cite the specific changed lines that settle the Blocker.")
  }
  return GoalSubtaskBlockerDisposition(
    findingId = (disposition["finding_id"] as? String)?.trim()?.takeIf(String::isNotBlank)
      ?: reviewStateError("$path.finding_id", "must be a non-blank prior Blocker finding id."),
    verdict = GoalSubtaskBlockerDispositionVerdict.fromWire(
      (disposition["verdict"] as? String)?.trim()
        ?: reviewStateError("$path.verdict", "must be resolved, unresolved, or superseded."),
    ),
    evidence = evidence,
  )
}

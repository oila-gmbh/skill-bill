package skillbill.application.goalrunner

import skillbill.application.featuretask.FeatureTaskRuntimeVerificationSignalKeys
import skillbill.contracts.JsonSupport
import skillbill.goalrunner.model.ReviewFindingOutcome
import skillbill.goalrunner.model.ReviewFindingOutcomeRecord
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.goalrunner.model.normalizedUnaddressedFindingCategory
import skillbill.goalrunner.model.normalizedUnaddressedFindingSeverity
import skillbill.goalrunner.model.toOutcomeRecord
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

  /**
   * The Review run ID the pass's `bill-code-review` invocation reported, read from the review phase's
   * declared `produced_outputs.review_run_id`. This is the shared key half that resolves a
   * workflow-loop finding to the findings and review_runs rows produced by the very review that
   * reported it; the finding id is the other half. A pass that genuinely reported no run id leaves it
   * null so the pair reads as unresolved rather than being bucketed to a guessed run.
   */
  private val Map<String, Any?>.reviewRunId: String?
    get() = (
      this["produced_outputs"]
        ?.let(JsonSupport::anyToStringAnyMap)
        ?.get(FeatureTaskRuntimeVerificationSignalKeys.REVIEW_RUN_ID) as? String
      )?.trim()?.takeIf(String::isNotBlank)

  fun unaddressedFindings(
    output: Map<String, Any?>,
    issueKey: String,
    subtaskId: Int,
    workflowId: String,
    reviewPassNumber: Int,
  ): List<UnaddressedFinding> {
    val reviewRunId = output.reviewRunId
    return structuredFindings(output).mapIndexed { index, finding ->
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
        reviewRunId = reviewRunId,
        findingId = finding.findingId,
      )
    }
  }

  /**
   * Derives one accepted/rejected/carried outcome per finding the run produced, entirely from loop
   * state — never inferred from agent prose. Every pass re-reviews the same delta, so a finding an
   * earlier pass reported and this pass no longer reports was addressed by the fix loop; a finding
   * this pass still reports is carried until a later pass retires it, and the final pass's survivors
   * stay carried into the terminal state. A Blocker the reserved remediation pass explicitly
   * dispositioned overrides that inference with the verdict the loop actually recorded.
   *
   * Cross-pass matching keys on [UnaddressedFinding.findingKey], never on the reported finding id:
   * each pass is its own review run and renumbers from `F-001`, so id matching would compare
   * positions and invert exactly the outcomes this derivation exists to record.
   *
   * This is what closes the coverage gap that left only 13% of runs with `feedback_events`: coverage
   * is now driven by the loop rather than by optional manual triage. The records outlive the ledger
   * rows they were derived from, which is why they are written to their own table.
   */
  fun reviewFindingOutcomes(
    supersededFindings: List<UnaddressedFinding>,
    currentFindings: List<UnaddressedFinding>,
    blockerDispositions: List<GoalSubtaskBlockerDisposition>,
  ): List<ReviewFindingOutcomeRecord> {
    val dispositionsByFindingId = blockerDispositions.associateBy(GoalSubtaskBlockerDisposition::findingId)
    val stillReported = currentFindings.mapTo(mutableSetOf(), UnaddressedFinding::findingKey)
    // Dispositions name the finding ids the *prior* pass emitted, and this pass renumbers from F-001.
    // Resolving them against the prior pass's findings first turns them into cross-pass identity keys,
    // so a disposition can never land on whichever current finding happens to share an ordinal.
    val dispositionVerdictsByKey = supersededFindings.mapNotNull { finding ->
      dispositionsByFindingId[finding.findingId]?.let { finding.findingKey to it.verdict }
    }.toMap()
    fun supersededOutcome(finding: UnaddressedFinding): ReviewFindingOutcome =
      when (dispositionVerdictsByKey[finding.findingKey]) {
        GoalSubtaskBlockerDispositionVerdict.RESOLVED -> ReviewFindingOutcome.ADDRESSED
        GoalSubtaskBlockerDispositionVerdict.SUPERSEDED -> ReviewFindingOutcome.REJECTED
        GoalSubtaskBlockerDispositionVerdict.UNRESOLVED -> ReviewFindingOutcome.CARRIED
        null -> ReviewFindingOutcome.ADDRESSED
      }

    // A finding this pass still reports was not addressed, whatever the disposition claimed. Only an
    // explicit supersede — the loop declining the finding — is a terminal outcome for a survivor.
    fun currentOutcome(finding: UnaddressedFinding): ReviewFindingOutcome =
      if (dispositionVerdictsByKey[finding.findingKey] == GoalSubtaskBlockerDispositionVerdict.SUPERSEDED) {
        ReviewFindingOutcome.REJECTED
      } else {
        ReviewFindingOutcome.CARRIED
      }
    val supersededOutcomes = supersededFindings
      .filter { finding -> finding.findingKey !in stillReported }
      .map { finding -> finding.toOutcomeRecord(supersededOutcome(finding)) }
    val currentOutcomes = currentFindings
      .map { finding -> finding.toOutcomeRecord(currentOutcome(finding)) }
    return supersededOutcomes + currentOutcomes
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
    val explicit = sequenceOf(
      finding["class_or_symbol"],
      finding["symbol"],
      finding["class"],
    ).filterIsInstance<String>()
      .map(String::trim)
      .filter(classOrSymbol::matches)
      .firstOrNull(String::isNotBlank)
    explicit?.let { return it }
    return fileStem.find(message)?.groupValues?.get(1)?.substringBeforeLast('.')?.takeIf(String::isNotBlank)
      ?: "Review"
  }

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

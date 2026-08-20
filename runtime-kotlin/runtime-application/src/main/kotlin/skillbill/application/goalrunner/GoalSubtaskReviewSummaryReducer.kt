package skillbill.application.goalrunner

import skillbill.application.featuretask.FeatureTaskRuntimeVerificationSignalKeys
import skillbill.contracts.JsonSupport
import skillbill.goalrunner.model.ReviewFindingOutcome
import skillbill.goalrunner.model.ReviewFindingOutcomeRecord
import skillbill.goalrunner.model.UNADDRESSED_FINDING_DEFAULT_CATEGORY
import skillbill.goalrunner.model.UNADDRESSED_FINDING_DEFAULT_SEVERITY
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.goalrunner.model.normalizedUnaddressedFindingCategory
import skillbill.goalrunner.model.normalizedUnaddressedFindingSeverity
import skillbill.goalrunner.model.toOutcomeRecord
import skillbill.ports.persistence.UnitOfWork
import skillbill.review.ReviewFindingActionability
import skillbill.review.ReviewFindingFieldCodec
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_PASS_VERDICTS
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDispositionVerdict
import skillbill.workflow.taskruntime.model.GoalSubtaskCommitFocusedAccounting
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.reviewStateError

internal data class StructuredGoalReviewFinding(
  val severity: String,
  val message: String,
  val issueCategory: String,
  val location: String,
  val compactLabel: String,
  val findingId: String? = null,
  val claimVerdict: ReviewClaimVerdict? = null,
  val scopeDisposition: ReviewScopeDisposition? = null,
  val citations: List<ReviewFindingCitation> = emptyList(),
  val severityAdjustment: ReviewSeverityAdjustment? = null,
)

internal data class GoalSubtaskReviewOutputOutcome(
  val verdict: FeatureTaskRuntimeVerdict,
  val unresolvedFindingCount: Int,
)

internal data class UnaddressedFindingLedgerScope(
  val issueKey: String,
  val subtaskId: Int,
  val workflowId: String,
  val reviewPassNumber: Int,
)

@Suppress("TooManyFunctions") // one reduction pipeline; each step is a named redaction stage
internal object GoalSubtaskReviewSummaryReducer {
  private const val MAX_TEXT_LENGTH: Int = 180
  private val pathLikeToken = Regex("(?:[A-Za-z]:)?(?:[/\\\\][^\\s:|]+)+|(?:[A-Za-z0-9_.-]+[/\\\\])+[A-Za-z0-9_.-]+")
  private val hunk = Regex("@@[^@]+@@")
  private val lineLocation = Regex(
    "(?:\\b(?:lines?|ln)\\s*:?\\s*\\d+(?:\\s*[-–]\\s*\\d+)?)|" +
      "(?:(?:\\bL|#)\\s*\\d+(?:\\s*[-–]\\s*(?:L|#)?\\s*\\d+)?)|" +
      "(?:\\b(?:columns?|cols?)\\s*:?\\s*\\d+(?:\\s*[-–]\\s*\\d+)?)|" +
      "(?::\\s*\\d+(?::\\s*\\d+)?(?:\\s*[-–]\\s*\\d+)?)|" +
      "(?:[\\(\\[\\{]\\s*\\d+(?:\\s*,\\s*\\d+)?\\s*[\\)\\]\\}])",
    RegexOption.IGNORE_CASE,
  )
  private val classOrSymbol = Regex("^[A-Z][A-Za-z0-9_]*(?:[.#][A-Za-z_][A-Za-z0-9_]*)?$")
  private val fileStem = Regex("(?:^|[/\\\\])([A-Za-z0-9_.-]+)\\.[A-Za-z0-9]+(?::\\d+(?:-\\d+)?)?")
  private val bareFilenameToken = Regex("\\b[A-Za-z0-9][A-Za-z0-9_.-]*\\.[A-Za-z0-9]+\\b")
  private val diffFragment = Regex("(?i)(?:\\bdiff\\s+--git\\b|\\bindex\\s+[0-9a-f]{7,}\\b|---|\\+\\+\\+)")

  fun fromOutput(
    output: Map<String, Any?>,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
  ): List<GoalSubtaskReviewCompactFinding> {
    return structuredFindings(output, recordedVerdicts)
      .filter { finding ->
        ReviewFindingActionability.isActionable(finding.claimVerdict, finding.scopeDisposition)
      }
      .map { finding ->
        GoalSubtaskReviewCompactFinding(
          severity = finding.severity,
          label = finding.compactLabel,
          text = sanitize(finding.message),
          findingId = finding.findingId,
        )
      }.groupBy { finding ->
        // Repair-receipt coverage reads these persisted findings. Distinct register ids must survive
        // even when compact labels collide; label collapse is only the legacy no-id path.
        finding.findingId?.lowercase() ?: finding.label.lowercase()
      }
      .values
      .map { sameKeyFindings ->
        sameKeyFindings.minByOrNull(::severityRank)
          ?: error("A grouped compact review summary must contain at least one finding.")
      }
  }

  /**
   * The delegated review pass's own commit-focused accounting, as it reported it. Absent for an
   * inline or non-commit pass, which is exactly the shape durable lifecycle state expects: a
   * missing record rather than a fabricated commit sequence identity. A malformed record fails
   * loudly through [GoalSubtaskCommitFocusedAccounting] rather than persisting half a sequence.
   */
  fun commitFocusedAccounting(output: Map<String, Any?>): GoalSubtaskCommitFocusedAccounting? =
    output["produced_outputs"]
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.get("commit_focused_accounting")
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.let { GoalSubtaskCommitFocusedAccounting.fromArtifactMap(it, "produced_outputs.commit_focused_accounting") }

  fun structuredFindings(
    output: Map<String, Any?>,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
  ): List<StructuredGoalReviewFinding> {
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
      val overlay = ReviewFindingActionability.overlayOf(
        findingRef = ReviewFindingFieldCodec.findingRefOf(
          finding["id"],
          finding["finding_id"],
          finding["f_number"],
        ),
        recordedVerdicts = recordedVerdicts,
        encoded = ReviewFindingFieldCodec.recordedFieldsOf(
          claimVerdict = finding["claim_verdict"],
          scopeDisposition = finding["scope_disposition"],
          citations = finding["citations"],
          severityAdjustment = finding["severity_adjustment"],
        ),
      )
      StructuredGoalReviewFinding(
        severity = severity,
        message = message,
        issueCategory = sequenceOf(finding["issue_category"], finding["category"])
          .filterIsInstance<String>().firstOrNull()?.trim()?.lowercase() ?: "other",
        location = sequenceOf(finding["location"], finding["artifact_ref"])
          .filterIsInstance<String>().firstOrNull()?.trim()?.takeIf(String::isNotBlank) ?: "<unknown>",
        compactLabel = labelFor(finding, message),
        findingId = ReviewFindingFieldCodec.findingRefOf(
          finding["id"],
          finding["finding_id"],
          finding["f_number"],
        ),
        claimVerdict = overlay.claimVerdict,
        scopeDisposition = overlay.scopeDisposition,
        citations = overlay.citations,
        severityAdjustment = overlay.severityAdjustment,
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
  fun reviewRunIdOf(output: Map<String, Any?>): String? = (
    output["produced_outputs"]
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.get(FeatureTaskRuntimeVerificationSignalKeys.REVIEW_RUN_ID) as? String
    )?.trim()?.takeIf(String::isNotBlank)

  fun recordedVerdicts(unitOfWork: UnitOfWork, output: Map<String, Any?>): List<ReviewFindingVerdict> {
    val reviewRunId = reviewRunIdOf(output) ?: return emptyList()
    return unitOfWork.reviews.fetchFindingVerdicts(reviewRunId)
  }

  fun unaddressedFindings(
    output: Map<String, Any?>,
    scope: UnaddressedFindingLedgerScope,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
  ): List<UnaddressedFinding> {
    val reviewRunId = reviewRunIdOf(output)
    return structuredFindings(output, recordedVerdicts).mapIndexed { index, finding ->
      UnaddressedFinding(
        issueKey = scope.issueKey,
        subtaskId = scope.subtaskId,
        workflowId = scope.workflowId,
        reviewPassNumber = scope.reviewPassNumber,
        findingOrdinal = index + 1,
        severity = normalizedUnaddressedFindingSeverity(finding.severity),
        issueCategory = normalizedUnaddressedFindingCategory(finding.issueCategory),
        location = finding.location,
        summary = finding.message,
        reviewRunId = reviewRunId,
        findingId = finding.findingId,
        claimVerdict = finding.claimVerdict,
        scopeDisposition = finding.scopeDisposition,
        citations = finding.citations,
        severityAdjustment = finding.severityAdjustment,
      )
    }
  }

  @Suppress("CyclomaticComplexMethod")
  fun rejectedVerificationFindings(
    verifyOutput: Map<String, Any?>,
    reviewOutput: Map<String, Any?>,
    scope: UnaddressedFindingLedgerScope,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
  ): List<UnaddressedFinding> {
    val reviewRunId = reviewRunIdOf(reviewOutput)
    val reviewById = structuredFindings(reviewOutput, recordedVerdicts).associateBy { it.findingId.orEmpty() }
    val dispositionsRaw = verifyOutput["produced_outputs"]
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.get(FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS) as? List<*>
      ?: return emptyList()
    return dispositionsRaw.mapIndexedNotNull { index, entry ->
      val map = JsonSupport.anyToStringAnyMap(entry) ?: return@mapIndexedNotNull null
      if ((map["disposition"] as? String)?.trim()?.lowercase() != "rejected") return@mapIndexedNotNull null
      val findingId = (map["finding_id"] as? String)?.takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
      val reason = (map["reason"] as? String)?.takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
      val reviewFinding = reviewById[findingId]
      val existingOrdinal = reviewFinding?.let {
        structuredFindings(reviewOutput, recordedVerdicts).indexOfFirst { candidate ->
          candidate.findingId == findingId
        }.takeIf { it >= 0 }?.plus(1)
      }
      val severity = (map["severity"] as? String)?.takeIf(String::isNotBlank)
        ?: reviewFinding?.severity
        ?: UNADDRESSED_FINDING_DEFAULT_SEVERITY
      UnaddressedFinding(
        issueKey = scope.issueKey,
        subtaskId = scope.subtaskId,
        workflowId = scope.workflowId,
        reviewPassNumber = scope.reviewPassNumber,
        findingOrdinal = existingOrdinal ?: (index + 1),
        severity = normalizedUnaddressedFindingSeverity(severity),
        issueCategory = normalizedUnaddressedFindingCategory(
          reviewFinding?.issueCategory ?: UNADDRESSED_FINDING_DEFAULT_CATEGORY,
        ),
        location = (map["location"] as? String)?.takeIf(String::isNotBlank) ?: reviewFinding?.location ?: "<unknown>",
        summary = (map["message"] as? String)?.takeIf(String::isNotBlank) ?: reviewFinding?.message ?: reason,
        reviewRunId = reviewRunId,
        findingId = findingId,
        claimVerdict = reviewFinding?.claimVerdict,
        scopeDisposition = reviewFinding?.scopeDisposition,
        citations = reviewFinding?.citations.orEmpty(),
        severityAdjustment = reviewFinding?.severityAdjustment,
        verificationDisposition = "rejected",
        verificationReason = reason,
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
        GoalSubtaskBlockerDispositionVerdict.UNRESOLVED -> ReviewFindingOutcome.CARRIED
        null -> ReviewFindingOutcome.ADDRESSED
      }

    fun currentOutcome(@Suppress("UNUSED_PARAMETER") finding: UnaddressedFinding): ReviewFindingOutcome =
      ReviewFindingOutcome.CARRIED
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

  fun refutedBlockerSupersedes(
    priorFindings: List<UnaddressedFinding>,
    currentFindings: List<UnaddressedFinding>,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
  ): List<GoalSubtaskBlockerDisposition> {
    if (priorFindings.isEmpty()) return emptyList()
    val currentByKey = currentFindings.associateBy(UnaddressedFinding::findingKey)
    val byRef = recordedVerdicts.groupBy(ReviewFindingVerdict::findingRef)
    return priorFindings.mapNotNull { prior ->
      if (prior.severity != "blocker") return@mapNotNull null
      val current = currentByKey[prior.findingKey] ?: return@mapNotNull null
      val findingId = prior.findingId ?: return@mapNotNull null
      val currentId = current.findingId ?: return@mapNotNull null
      val verification = ReviewFindingActionability.verificationVerdict(byRef[currentId].orEmpty())
        ?: ReviewFindingActionability.verificationVerdict(byRef[findingId].orEmpty())
      if (verification?.claimVerdict != ReviewClaimVerdict.REFUTED) return@mapNotNull null
      val evidence = verification.citations.map { citation -> "${citation.path}:${citation.line}" }
      if (evidence.isEmpty()) return@mapNotNull null
      GoalSubtaskBlockerDisposition(
        findingId = findingId,
        verdict = GoalSubtaskBlockerDispositionVerdict.RESOLVED,
        evidence = evidence,
      )
    }
  }

  fun unresolvedCount(output: Map<String, Any?>, recordedVerdicts: List<ReviewFindingVerdict> = emptyList()): Int =
    fromOutput(output, recordedVerdicts)
      .count(GoalSubtaskReviewCompactFinding::blocksAdvance)

  fun outcomeFor(
    output: Map<String, Any?>,
    findings: List<GoalSubtaskReviewCompactFinding> = fromOutput(output),
  ): GoalSubtaskReviewOutputOutcome {
    // Blocker and Major reopen remediation and block advance; Minor and Nit stay ledger-only and never
    // alone force changes_requested. The durable pass count must agree with that gate so
    // acceptsOperatorDecision and non-convergence pause see the same advance-blocking evidence the
    // phase transition already used.
    val advanceBlockingCount = findings.count(GoalSubtaskReviewCompactFinding::blocksAdvance)
    val hasOnlyNonBlockingFindings = findings.isNotEmpty() && advanceBlockingCount == 0
    val verdict = reviewPassVerdict(output, findings, advanceBlockingCount, hasOnlyNonBlockingFindings)
    return GoalSubtaskReviewOutputOutcome(
      verdict = verdict,
      unresolvedFindingCount = when {
        advanceBlockingCount > 0 -> advanceBlockingCount
        hasOnlyNonBlockingFindings ||
          verdict == FeatureTaskRuntimeVerdict.APPROVED ||
          verdict == FeatureTaskRuntimeVerdict.REVIEW_SKIPPED_BY_USER -> 0
        else -> 1
      },
    )
  }

  private fun reviewPassVerdict(
    output: Map<String, Any?>,
    findings: List<GoalSubtaskReviewCompactFinding>,
    advanceBlockingCount: Int,
    hasOnlyNonBlockingFindings: Boolean,
  ): FeatureTaskRuntimeVerdict {
    val declaredVerdict = (output["verdict"] as? String)?.trim()
    val changesRequested = declaredVerdict in setOf("needs_fix", FeatureTaskRuntimeVerdict.CHANGES_REQUESTED.wireValue)
    val reportedFindingsWereFiltered = findings.isEmpty() && structuredFindings(output).isNotEmpty()
    return when {
      advanceBlockingCount > 0 -> FeatureTaskRuntimeVerdict.CHANGES_REQUESTED
      hasOnlyNonBlockingFindings || reportedFindingsWereFiltered -> FeatureTaskRuntimeVerdict.APPROVED
      changesRequested -> FeatureTaskRuntimeVerdict.CHANGES_REQUESTED
      declaredVerdict?.isNotBlank() == true -> FeatureTaskRuntimeVerdict.fromWire(declaredVerdict)
        .takeIf(GOAL_SUBTASK_REVIEW_PASS_VERDICTS::contains)
        ?: FeatureTaskRuntimeVerdict.APPROVED
      else -> FeatureTaskRuntimeVerdict.APPROVED
    }
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
        ?: reviewStateError("$path.verdict", "must be resolved or unresolved."),
    ),
    evidence = evidence,
  )
}

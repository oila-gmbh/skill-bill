package skillbill.workflow.taskruntime.model

private const val CHURN_REASON_MAX_NAMED_CONSTRUCTS: Int = 8

data class FeatureTaskRuntimeAuditRepairProgressDecision(
  val blocked: Boolean,
  val reason: String?,
)

data class FeatureTaskRuntimeAuditRepairGapIdentities(
  val gapIds: Set<String>,
  val criterionRefs: Set<String>,
)

/**
 * Advance-blocking finding identities compared across consecutive remediation review passes.
 * Finding ids renumber every pass, so identity is severity + label + text (already sanitized).
 * Minor and Nit never enter this set.
 */
data class FeatureTaskRuntimeReviewRemediationFindingIdentities(
  val identities: Set<String>,
)

// Gap-id equality alone is defeatable: dispositioning a gap resolved and re-reporting the same criterion
// under the next generation churns every identifier while the defect set is untouched. The criterion-ref
// set is the churn-proof key, so either an unchanged identity set or an unchanged criterion set counts as
// an equivalent gap set.
fun detectAuditRepairNonProgress(
  previous: FeatureTaskRuntimeAuditRepairGapIdentities,
  current: FeatureTaskRuntimeAuditRepairGapIdentities,
  previousRepositoryFingerprint: String,
  currentRepositoryFingerprint: String,
  newlyResolvedRepairItemCount: Int,
): FeatureTaskRuntimeAuditRepairProgressDecision {
  val equivalentGapIds = previous.gapIds == current.gapIds
  val equivalentCriteria = previous.criterionRefs.isNotEmpty() && previous.criterionRefs == current.criterionRefs
  val equivalentGaps = equivalentGapIds || equivalentCriteria
  val repositoryUnchanged = previousRepositoryFingerprint == currentRepositoryFingerprint
  val blocked = equivalentGaps && (repositoryUnchanged || newlyResolvedRepairItemCount == 0)
  val identityDetail = if (equivalentGapIds) {
    "unresolved gap identities are unchanged"
  } else {
    "the unresolved acceptance criteria are unchanged (${current.criterionRefs.sorted()}) under renamed gap ids"
  }
  return FeatureTaskRuntimeAuditRepairProgressDecision(
    blocked = blocked,
    reason = if (blocked) {
      "Audit repair made no progress: $identityDetail and " +
        if (repositoryUnchanged) "the repository fingerprint is unchanged." else "no repair item was newly resolved."
    } else {
      null
    },
  )
}

/**
 * Review-side non-convergence: the same advance-blocking (Blocker or Major) finding identity set
 * across consecutive remediation passes with an unchanged repository fingerprint or reviewed-delta
 * digest pauses for an operator decision instead of re-entering `implement_fix`. An empty current
 * set is progress (findings cleared). An absent fingerprint is unproven change and compares equal to
 * itself, so the bound fails closed rather than looping forever.
 */
fun detectReviewRemediationNonProgress(
  previous: FeatureTaskRuntimeReviewRemediationFindingIdentities,
  current: FeatureTaskRuntimeReviewRemediationFindingIdentities,
  previousRepositoryFingerprintOrDigest: String,
  currentRepositoryFingerprintOrDigest: String,
): FeatureTaskRuntimeAuditRepairProgressDecision {
  if (current.identities.isEmpty()) {
    return FeatureTaskRuntimeAuditRepairProgressDecision(blocked = false, reason = null)
  }
  val equivalentFindings = previous.identities.isNotEmpty() && previous.identities == current.identities
  val repositoryUnchanged = previousRepositoryFingerprintOrDigest == currentRepositoryFingerprintOrDigest
  val blocked = equivalentFindings && repositoryUnchanged
  return FeatureTaskRuntimeAuditRepairProgressDecision(
    blocked = blocked,
    reason = if (blocked) {
      "Review remediation made no progress: the unresolved Blocker or Major finding set is unchanged " +
        "and the repository fingerprint or reviewed delta digest is unchanged."
    } else {
      null
    },
  )
}

data class FeatureTaskRuntimeReviewRemediationChurnEvidence(
  val roundCount: Int,
  val blockerCount: Int,
  val majorCount: Int,
  val constructSymbols: List<String>,
) {
  init {
    require(roundCount >= 1) {
      "FeatureTaskRuntimeReviewRemediationChurnEvidence.roundCount must be a positive integer."
    }
    require(constructSymbols.isNotEmpty()) {
      "Churn evidence must name the recurring constructs it was detected against."
    }
  }

  fun pauseReasonClause(): String =
    "Remediation is churning rather than converging: across $roundCount consecutive round(s) the " +
      "advance-blocking findings ($blockerCount Blocker, $majorCount Major) recur against construct(s) " +
      "an earlier round already recorded as repaired (${constructSymbols.joinToString(", ")}), and the " +
      "advance-blocking finding set is not shrinking."
}

private data class ReviewRemediationChurnRound(
  val blockingFindings: List<GoalSubtaskReviewCompactFinding>,
  val recurringEntries: List<FeatureTaskRuntimeRepairLedgerEntry>,
)

fun featureTaskRuntimeReviewRemediationChurn(
  ledger: FeatureTaskRuntimeRepairLedger,
  passResults: List<GoalSubtaskReviewPassResult>,
  minimumConsecutiveRounds: Int,
): FeatureTaskRuntimeReviewRemediationChurnEvidence? {
  require(minimumConsecutiveRounds >= 1) {
    "featureTaskRuntimeReviewRemediationChurn requires a positive round threshold, was $minimumConsecutiveRounds."
  }
  if (ledger.isEmpty) return null
  val window = trailingChurnWindow(ledger, passResults.sortedBy(GoalSubtaskReviewPassResult::passNumber))
  if (window.size < minimumConsecutiveRounds || advanceBlockingCountFell(window)) return null
  val latest = window.last().blockingFindings
  return FeatureTaskRuntimeReviewRemediationChurnEvidence(
    roundCount = window.size,
    blockerCount = latest.count { it.severity == GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY },
    majorCount = latest.count { it.severity == "major" },
    constructSymbols = window
      .flatMap(ReviewRemediationChurnRound::recurringEntries)
      .flatMap { entry -> entry.constructs.map(FeatureTaskRuntimeRepairConstruct::symbol) }
      .distinct()
      .sorted()
      .take(CHURN_REASON_MAX_NAMED_CONSTRUCTS),
  )
}

private fun trailingChurnWindow(
  ledger: FeatureTaskRuntimeRepairLedger,
  ordered: List<GoalSubtaskReviewPassResult>,
): List<ReviewRemediationChurnRound> = ordered.asReversed()
  .asSequence()
  .map { pass -> churnRoundOrNull(ledger, pass) }
  .takeWhile { round -> round != null }
  .filterNotNull()
  .toList()
  .asReversed()

private fun churnRoundOrNull(
  ledger: FeatureTaskRuntimeRepairLedger,
  pass: GoalSubtaskReviewPassResult,
): ReviewRemediationChurnRound? {
  val blocking = pass.findings.filter(GoalSubtaskReviewCompactFinding::blocksAdvance)
  if (blocking.isEmpty()) return null
  val recurring = ledger.entries.filter { entry ->
    entry.constructs.isNotEmpty() &&
      entry.originRound < pass.passNumber &&
      blocking.any(entry::disturbedBy)
  }
  return recurring.takeIf(List<FeatureTaskRuntimeRepairLedgerEntry>::isNotEmpty)
    ?.let { entries -> ReviewRemediationChurnRound(blocking, entries) }
}

private fun advanceBlockingCountFell(window: List<ReviewRemediationChurnRound>): Boolean = window
  .map { round -> round.blockingFindings.size }
  .zipWithNext()
  .any { (earlier, later) -> later < earlier }

/** Stable cross-pass identity for one advance-blocking compact finding. */
fun advanceBlockingFindingIdentity(finding: GoalSubtaskReviewCompactFinding): String {
  require(finding.blocksAdvance) {
    "advanceBlockingFindingIdentity requires a Blocker or Major finding, was '${finding.severity}'."
  }
  return compactReviewFindingIdentity(finding)
}

fun advanceBlockingFindingIdentities(
  findings: List<GoalSubtaskReviewCompactFinding>,
): FeatureTaskRuntimeReviewRemediationFindingIdentities = FeatureTaskRuntimeReviewRemediationFindingIdentities(
  identities = findings.filter(GoalSubtaskReviewCompactFinding::blocksAdvance)
    .mapTo(linkedSetOf(), ::advanceBlockingFindingIdentity),
)

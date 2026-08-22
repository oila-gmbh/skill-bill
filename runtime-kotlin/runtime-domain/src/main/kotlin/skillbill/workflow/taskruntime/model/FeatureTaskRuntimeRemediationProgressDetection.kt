package skillbill.workflow.taskruntime.model

private const val CHURN_REASON_MAX_NAMED_CONSTRUCTS: Int = 8

/**
 * A repository fingerprint that could not be proven: change cannot be asserted, so a comparison
 * against it fails closed rather than letting an unchanged audit pass as progress.
 */
const val UNPROVEN_REPOSITORY_FINGERPRINT: String = "<unproven>"

data class FeatureTaskRuntimeAuditRepairProgressDecision(
  val blocked: Boolean,
  val reason: String?,
)

/**
 * Advance-blocking finding identities compared across consecutive remediation review passes.
 * Finding ids renumber every pass, so identity is severity + label + text (already sanitized).
 * Minor and Nit never enter this set.
 */
data class FeatureTaskRuntimeReviewRemediationFindingIdentities(
  val identities: Set<String>,
)

/**
 * Audit-side non-convergence: whether re-entering `implement` after another `audit_gap` is progress
 * or a stall. Identity is the canonical criterion ref, and the whole key is the set of refs the
 * audit still reports unmet. An empty current set is convergence, not a stall. Any prior ref cleared
 * is progress even when new criteria appeared. With no cleared prior ref — an identical set, or a
 * pure substitution of equal or larger cardinality — the audit has not shrunk its unmet set, so it
 * stalls when the repository stands still, and fails closed when change cannot be proven.
 */
fun detectAuditRepairNonProgress(
  previousCriterionRefs: Set<String>,
  currentCriterionRefs: Set<String>,
  previousRepositoryFingerprint: String,
  currentRepositoryFingerprint: String,
): FeatureTaskRuntimeAuditRepairProgressDecision {
  // An empty current set is convergence: the audit satisfied every criterion, so nothing blocks.
  if (currentCriterionRefs.isEmpty()) {
    return FeatureTaskRuntimeAuditRepairProgressDecision(blocked = false, reason = null)
  }
  // An empty previous set means there is no prior round to compare against, so nothing blocks.
  if (previousCriterionRefs.isEmpty()) {
    return FeatureTaskRuntimeAuditRepairProgressDecision(blocked = false, reason = null)
  }
  // Any prior criterion ref cleared is progress, even when new criteria appeared alongside.
  val clearedPriorRef = (previousCriterionRefs - currentCriterionRefs).isNotEmpty()
  if (clearedPriorRef) {
    return FeatureTaskRuntimeAuditRepairProgressDecision(blocked = false, reason = null)
  }
  // No cleared prior ref (identical set, or substitution without a shrink): stall when the repository
  // is unchanged, or when the previous fingerprint is unproven (fails closed). Only a proven change
  // continues.
  val repositoryUnchanged = previousRepositoryFingerprint == currentRepositoryFingerprint
  val previousUnproven = previousRepositoryFingerprint == UNPROVEN_REPOSITORY_FINGERPRINT
  val blocked = repositoryUnchanged || previousUnproven
  return FeatureTaskRuntimeAuditRepairProgressDecision(
    blocked = blocked,
    reason = if (blocked) {
      "Audit made no progress: the unmet acceptance criteria are unchanged " +
        "(${currentCriterionRefs.sorted()}) and the repository fingerprint is unchanged."
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

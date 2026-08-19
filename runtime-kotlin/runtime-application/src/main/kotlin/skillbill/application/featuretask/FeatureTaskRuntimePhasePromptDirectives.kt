package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimeImplementationContinuation
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_REPAIR_PLAN_CONTRACT_VERSION
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorReviewContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedger

// Phase-scoped prompt directives and the per-phase task directive table, split out of
// FeatureTaskRuntimePhasePromptComposer so the composer object stays within its size budget.
// Validate Task-line specialization lives in FeatureTaskRuntimePhasePromptValidateDirectives.

// Emitted only for mutating phases (see [FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase]):
// implement and implement_fix. The directive is empty for every other phase so their prompts stay
// byte-for-byte unchanged.
internal fun mutatingPhaseIdempotencyDirective(phaseId: String): String {
  if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(phaseId)) {
    return ""
  }
  return """
    ## Mutating-phase idempotency contract
    You are given intended-state plan inputs (the target the repository should reach) plus the
    CURRENT working tree, which may already carry some or all of those changes from a prior
    attempt that was interrupted mid-edit. Converge the tree to the target state; treat any change
    that is already applied as a no-op and NEVER blindly re-apply it (no duplicated edits, appended
    blocks, or re-created files). This phase may be re-entered or resumed after a crash, so it must
    be safe to run again: reconciling to target, not re-applying from scratch. Before finishing,
    verify every changed file is at its intended state and report that reconciled end-state in
    produced_outputs (see the reconciliation report in the required output below).
  """.trimIndent()
}

// Identical Minimalism discipline block shared by implement and implement_fix (AC-004 separate-source
// branch). FeatureTaskRuntimePhasePromptDirectives is the sole runtime-owned source for this
// mutating-phase briefing text.
internal fun minimalismDisciplineDirective(phaseId: String): String {
  if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(phaseId)) {
    return ""
  }
  return """
    ## Minimalism discipline (reuse before write)
    Understand the problem first, then climb the ladder. Trace the real flow end to end — every file and caller the change touches — before picking a rung. Laziness that skips comprehension ships a confident wrong fix. Read fully, then be lazy.

    The ladder — stop at the first rung that holds:
    1. Does this need to exist at all? Speculative need = skip it and say so in one line (YAGNI).
    2. Already in this codebase? Reuse the helper, util, type, or pattern that already lives here.
    3. Stdlib does it? Use it.
    4. Native platform feature covers it? Prefer platform primitives over a new dependency or custom layer.
    5. An already-installed dependency solves it? Use it. Never add a new dependency for what a few lines can do.
    6. Can it be one line? One line.
    7. Only then: the minimum code that works.
    Two equal rungs both work → take the higher one and move on.

    Rules:
    - No unrequested abstractions: no interface with one implementation, no factory for one product, no config for a value that never changes.
    - No scaffolding "for later"; later can scaffold for itself.
    - Deletion over addition. Boring over clever.
    - Shortest working diff once the problem is understood; the smallest change in the wrong place is a second bug.
    - Between two equal-size options, take the one correct on edge cases.

    Bug fix = root cause, not symptom. Before editing, grep every caller of the function you are about to touch. Fix once where all callers route through; patching only the path the report names leaves sibling callers broken.

    Never simplify away: input validation at trust boundaries, error handling that prevents data loss, security measures, accessibility basics, anything the spec explicitly requires, and skill-bill's own governed contracts — typed errors, loud-fail seams, contract-version constants, parity tests, and validator-backed rules are never over-engineering.

    Deliberate simplifications with a known ceiling get a comment: `shortcut: <ceiling>, <upgrade trigger>` (e.g. `// shortcut: global lock, per-account locks if throughput matters`). Exception to comments-are-a-last-resort: `shortcut:` markers are permitted because they record a non-obvious why (ceiling and upgrade trigger).
  """.trimIndent()
}

// Write-time test-value bar for plan, implement, and implement_fix. Plan is included because
// test_obligations are decided there; isMutatingPhase alone would miss it. Empty for every other
// phase so evaluator briefings stay unchanged.
internal fun testValueDisciplineDirective(phaseId: String): String {
  if (phaseId !in TEST_VALUE_DISCIPLINE_PHASES) {
    return ""
  }
  return """
    ## Test-value discipline (every test must earn its cost)
    Tests are a recurring cost: every future change to the code they touch pays for them in
    maintenance and reasoning tokens. Write few, high-value tests; never mirror code 1:1 with tests.
    - Before writing a test, name the realistic bug it would catch — a concrete wrong behavior that
      fails this test while the rest of the suite passes. If you cannot, do not write the test.
    - Concentrate coverage on critical paths: money and quantities, data integrity and persistence
      atomicity, auth and tenant isolation, external contracts and serialization, concurrency and
      recovery, irreversible side effects. Trivial glue on non-critical paths needs no test; say so
      instead of writing one.
    - Assert observable behavior at boundaries, never implementation structure: no mock-interaction
      verification without an outcome assertion, no call-ordering assertions, no implementation
      logic duplicated inside the test.
    - One strong test per rule or branch; no sibling tests re-covering the same branch with
      different literals.
    - When planning, emit test_obligations only for behaviors that pass this bar, each tied to an
      acceptance criterion or a named realistic bug; an empty test_obligations list is a valid
      outcome for a task.
    - Never remove or weaken regression coverage tied to a real past bug, and never treat governed
      parity tests or validator-backed rules as omission candidates — the minimalism carve-outs
      apply to tests too.
  """.trimIndent()
}

private val TEST_VALUE_DISCIPLINE_PHASES: Set<String> = setOf(
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
)

/**
 * Emitted when a prior segment of THIS implementation left obligations open.
 *
 * Deliberately not the schema-correction directive: that one tells the agent its output was rejected
 * and to re-emit it, which is the wrong instruction and the wrong mental model for work that was
 * accepted as far as it went. This one says continue, and hands over the complete bounded prior
 * receipt so the next segment knows exactly what is already closed and must not be redone.
 *
 * Composed from the durable projection rather than declared as an upstream projection on an implement
 * self-edge: a self-edge required input would fail the first attempt, which has no prior receipt by
 * construction.
 */
internal fun implementationContinuationDirective(
  phaseId: String,
  continuation: FeatureTaskRuntimeImplementationContinuation?,
): String {
  if (continuation == null || continuation.phaseId != phaseId) return ""
  val closed = continuation.completedTaskIds.takeIf { it.isNotEmpty() }?.joinToString() ?: "none"
  val paths = continuation.changedPaths.takeIf { it.isNotEmpty() }?.joinToString() ?: "none recorded"
  val deviations = continuation.deviations.takeIf { it.isNotEmpty() }
    ?.joinToString("; ") { "${it.ref}: ${it.note}" } ?: "none"
  val unresolved = continuation.unresolvedItems.takeIf { it.isNotEmpty() }?.joinToString("; ") ?: "none"
  val reconciliation = continuation.reconciliationEvidence
    ?.let { "reconciled=${it.reconciled}; ${it.evidence}" } ?: "not reported"
  val checkpoint = continuation.repositoryCheckpoint?.fingerprint ?: "not reported"
  val disposition = continuation.failureDisposition ?: "none"
  return """
    ## Continue this implementation — segment ${continuation.segmentNumber}
    A prior segment of this same implementation ran and did real work. It was NOT rejected and its
    output was NOT malformed: it simply did not close every ${continuation.obligationNoun} yet.
    Continue from where it stopped. Do not restart the implementation, do not redo closed work, and do
    not re-apply changes already present — the mutating-phase idempotency contract still governs.

    Still open (${continuation.obligationNoun}s you must close in this segment): ${
    continuation.openObligationIds.joinToString()
  }

    Prior receipt:
    - completed ${continuation.obligationNoun} ids: $closed
    - changed paths: $paths
    - deviations: $deviations
    - unresolved items: $unresolved
    - reconciliation evidence: $reconciliation
    - repository checkpoint: $checkpoint
    - failure disposition: $disposition

    Your receipt for this segment must list every ${continuation.obligationNoun} that is closed once
    you are done — the ones above plus the ones you close now. Reporting `completed` while any listed
    obligation is still open will not advance the run.
  """.trimIndent()
}

/**
 * Why the previous attempt at a phase must be corrected, kept typed rather than as a bare string.
 *
 * A schema-gate rejection and a retryable `blocked`/`failed` envelope both re-enter the same bounded
 * semantic budget, but they are different events and must not be prompted, reported or dispositioned
 * alike: only the first is a rejection. Threading one nullable string made them indistinguishable at
 * the composer seam, which is how a schema-valid terminal envelope came to be told it was rejected.
 *
 * [correctiveRepairContext] is schema-gate only: the authorized bounded repair projection of the
 * rejected response. Retryable-terminal and incomplete-work paths must not carry it, so they never
 * receive a raw-output repair section.
 */
internal class PriorAttemptCorrection private constructor(
  private val reason: String,
  private val terminal: Boolean,
  val correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext? = null,
) {
  val schemaGateReason: String? get() = reason.takeUnless { terminal }
  val retryableTerminalReason: String? get() = reason.takeIf { terminal }

  init {
    require(correctiveRepairContext == null || !terminal) {
      "PriorAttemptCorrection: corrective repair context belongs only to schema-gate retries, " +
        "not retryable-terminal envelopes."
    }
  }

  companion object {
    fun schemaGate(
      reason: String,
      correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext? = null,
    ): PriorAttemptCorrection =
      PriorAttemptCorrection(reason, terminal = false, correctiveRepairContext = correctiveRepairContext)

    fun retryableTerminal(reason: String): PriorAttemptCorrection =
      PriorAttemptCorrection(reason, terminal = true, correctiveRepairContext = null)
  }
}

/**
 * Emitted when the prior attempt ended in a retryable `blocked` or `failed` envelope.
 *
 * Deliberately not the schema-correction directive: that envelope validated. Telling its author the
 * output was rejected and must be re-emitted describes an event that did not happen and invites a
 * cosmetic re-serialization of the same blocked state instead of an attempt at the blocker itself.
 */
internal fun terminalRetryDirective(priorTerminalFailure: String?): String {
  if (priorTerminalFailure.isNullOrBlank()) return ""
  return """
    ## Previous attempt reported a retryable block — try again
    Your previous attempt at this phase emitted valid output that reported the phase could not finish.
    It was NOT rejected and its format was NOT wrong. Reported reason:
    $priorTerminalFailure
    Re-attempt the phase against the current repository state. If the same obstacle still stands and you
    cannot clear it, report it again with the disposition that matches it rather than restating it in a
    different shape; a re-emitted block with no new attempt behind it will exhaust this phase's budget.
  """.trimIndent()
}

/**
 * Everything the review-execution directive needs to state the run's review depth and scope. These
 * travel together from [FeatureTaskRuntimePhasePromptComposer.compose] and are only ever read as a set.
 */
internal data class ReviewExecutionDirectiveInputs(
  val codeReviewMode: CodeReviewExecutionMode,
  val parallelReviewAgent: String?,
  val goalSubtaskReviewInput: GoalSubtaskReviewInput?,
  val reviewPassNumber: Int?,
  val resolvedReviewTier: CodeReviewExecutionMode?,
  val reviewDecidingRule: String?,
  val baselineUntrackedPaths: List<String> = emptyList(),
  val repairLedger: FeatureTaskRuntimeRepairLedger? = null,
  val priorReviewContext: FeatureTaskRuntimePriorReviewContext? = null,
)

// Emits for every commit phase: the runtime and agent never stage feature specs. A human operator
// may already have committed them; leave those HEAD files alone and leave remaining spec dirt local.
internal fun commitExclusionDirective(phaseId: String, issueKey: String): String {
  if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH) {
    return ""
  }
  return """
    ## Feature-spec commit exclusion
    Feature specs are workflow inputs, not implementation output. Never list any `.feature-specs/`
    path in `commit_push_result.changed_paths` — especially this feature's
    `.feature-specs/$issueKey-*` (or `.feature-specs/$issueKey/`) tree, including the parent spec,
    every subtask spec, and `decomposition-manifest.yaml`. The runtime stages the paths you enumerate
    and nothing else; it never runs `git add -A` / `git add .`. Leave `.feature-specs/` dirty locally
    if it changed. Never amend, reset, or restage a commit this runtime does not own, including a
    commit a human operator authored: leave those alone.
  """.trimIndent()
}

internal fun goalContinuationDirective(phaseId: String, suppressDecomposition: Boolean): String {
  if (!suppressDecomposition || phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN) {
    return ""
  }
  return """
    ## Goal-continuation planning constraint
    This run is already executing one governed decomposed subtask. Do not propose or emit a new
    decomposition package in the plan phase. Produce an implementable single-subtask plan for the
    current spec; `produced_outputs.mode` must not be "decompose".
    Never include installer, uninstall, or install-sync commands in the plan: do not plan to run
    `./install.sh`, `./uninstall.sh`, `skill-bill install`, `skill-bill install apply`, or any
    equivalent install refresh inside a goal-continuation child. The plan phase defines how future
    acceptance work will be implemented and validated; it does not require that work to have already
    happened. Never block planning merely because a later implementation or validation action is not
    yet complete. A blocked plan requires a genuinely missing input or an irreconcilable constraint
    that prevents an implementable plan from being produced.
  """.trimIndent()
}

// One imperative task directive per phase; the briefing carries the spec-specific scope.
// Validate Task-line specialization lives in FeatureTaskRuntimePhasePromptValidateDirectives.
internal val phaseDirectives: Map<String, String> = mapOf(
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to
    "Produce the scaled pre-planning digest for the resolved feature size. Do not modify " +
    "repository files during this phase. Emit a schema-valid produced_outputs object carrying the " +
    "bounded digest for the downstream plan phase: projection_kind \"preplanning_digest\", " +
    "contract_version \"$FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION\", and the " +
    "declared digest fields (affected_boundaries, patterns_and_decisions, risks, rollout, " +
    "validation_strategy, unresolved_questions, evidence_refs). Do not forward the complete " +
    "preplan envelope, a generic summary, or progress diagnostics.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to
    "Produce an ordered implementation plan that satisfies every acceptance criterion, using " +
    "the upstream preplan digest as planning context. Do not modify repository files during " +
    "this phase. Emit a schema-valid produced_outputs object carrying the bounded executable plan: " +
    "projection_kind \"executable_plan\", contract_version " +
    "\"$FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION\", mode (direct or decompose), " +
    "stable ordered tasks " +
    "(task_id, depends_on, description, criterion_refs, target_paths_or_symbols, test_obligations, " +
    "constraints), and validation_strategy. Exclude planning narration, presentation summary, and " +
    "generic producer notes; decomposition detail stays private to the preparation boundary.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to
    "Reconcile the repository to the intended state the upstream plan output describes: make the " +
    "changes it specifies, treating any already-applied change as a no-op. See the mutating-phase " +
    "idempotency contract below. Emit produced_outputs carrying the bounded implementation receipt " +
    "(projection_kind \"implementation_receipt\", contract_version " +
    "\"$FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION\": completed_task_ids, " +
    "normalized changed_paths, " +
    "tests_added, tests_updated, deviations, unresolved_items, " +
    "reconciliation_evidence, and the repository_checkpoint the audit will verify against). Every " +
    "receipt field is a bounded summary, not a transcript: a segment that applied no edits reports that " +
    "it applied none, names what already satisfied the work, and stops — the audit re-reads the tree " +
    "itself, so proving convergence path by path here only risks overflowing the field. When the " +
    "briefing carries audit_gaps, reuse its immutable initial preplan and plan outputs and change " +
    "only what the latest listed gaps require; do not regenerate planning, expand scope, or disturb " +
    "settled implementation. Under the audit-gap loop, report repair_item_results for every carried " +
    "repair item with a terminal fixed or already_satisfied outcome, or list it in " +
    "superseded_repair_items with its governing decision, authority_ref, and rationale. Reporting fewer " +
    "items than you were carried is a resumable partial repair, not a completion. Repair evidence is " +
    "read-only repository facts: do not run builds or tests here.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_FIX to
    "Decide the root cause of each carried review finding before any edit is made. Do not modify " +
    "repository files during this phase; the following implement_fix phase applies your plan. Read the " +
    "carried findings, the repair_ledger of what earlier rounds of this same remediation already fixed, " +
    "and the immutable initial preplan and plan outputs as read-only context — you never regenerate, " +
    "mutate, or overwrite them. Emit produced_outputs.repair_plan with contract_version " +
    "\"$FEATURE_TASK_RUNTIME_REPAIR_PLAN_CONTRACT_VERSION\", the round number, and exactly one entry per " +
    "carried finding: finding_ref, the root_cause, the minimal_change that addresses it, and a " +
    "classification of local_patch_site or design_symptom. Classify design_symptom when the finding is a " +
    "consequence of an earlier round's remedy rather than a local defect, and name that earlier finding " +
    "in prior_round_remedy_ref. A design_symptom classification escalates the round for an operator " +
    "decision instead of advancing to implement_fix, so use it when another local patch would be the " +
    "wrong repair — not merely when the fix is awkward.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX to
    "Address the carried findings from the preceding review pass on the CURRENT working tree as " +
    "incremental reconciliation. Every finding in the briefing — Blocker, Major, Minor, and Nit — is in " +
    "scope; specialist narratives and raw review output are not. Do not re-apply " +
    "the plan from scratch or expand scope beyond the carried findings. Treat any fix already present " +
    "as a no-op. See the mutating-phase idempotency contract below. From round two onward the briefing " +
    "also carries repair_ledger: what earlier rounds of this same remediation already fixed and which " +
    "named constructs hold each finding closed. Those entries are settled load-bearing work, not open " +
    "findings awaiting action — do not re-address a resolved entry and do not treat it as scope. If " +
    "closing a carried finding requires you to remove or materially rewrite a construct a resolved " +
    "entry names, say so: list that entry's finding_ref in produced_outputs.repair_receipt." +
    "disturbed_remedies with a one-line reason. Silent removal is rejected, because the finding that " +
    "construct closed can otherwise be reintroduced without anyone seeing it. Emit " +
    "produced_outputs.repair_receipt " +
    "with contract_version \"$FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION\" and exactly one " +
    "entry per carried finding: the finding's severity, label, " +
    "and sanitized text, an explicit outcome (addressed, or no_edit_required with no_edit_reason), " +
    "symbol-granularity closing constructs (Type or Type.member, optional file basename — never a bare " +
    "path), and a bounded one-line repair intent. A legitimately unedited finding still needs its " +
    "no_edit_required entry; omission is rejected.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to
    "The runtime owns this review. Do not run bill-code-review, do not emit findings, and do not " +
    "report unsatisfied acceptance criteria. Criterion-gap detection remains exclusive to the audit phase.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to
    "Run the encoded completeness audit ceremony and report production-behavior or production-implementation " +
    "acceptance-criterion gaps only. Never report test adequacy, coverage, fixtures, assertions, or other " +
    "test-only concerns as audit gaps. The upstream implementation receipt is a producer CLAIM, not " +
    "evidence: read the repository itself at the resolved checkpoint in the briefing — the diff over its " +
    "base_ref/head_ref plus its scoped_owned_paths — and compare that actual state against the plan " +
    "commitment and the acceptance criteria. A criterion is satisfied only by repository evidence you " +
    "read; never mark one satisfied because the receipt lists a completed task id, a changed path, or " +
    "reconciliation_evidence claiming reconciled. A claim contradicted by the tree is itself a gap. " +
    "Emit audit_result with clearance_status, review_scope, and the exact repository_checkpoint; " +
    "keep audit reasoning and repair history outside the clearance. Account for every carried gap the " +
    "briefing lists against repository evidence: a defect still present keeps its identity and is re-reported " +
    "in gaps under its existing gap_id, and one you verified fixed gets a carried_gap_dispositions entry with " +
    "status resolved and your own resolution evidence. Leaving a carried gap out claims nothing and is " +
    "rejected. Before emitting a satisfied clearance, also emit " +
    "blast_radius_inspection naming the repair batch's changed production paths, the gap ids any newly " +
    "introduced defect opens, and your evidence. All evidence is read-only repository facts: never run a " +
    "build, a test, or any other command as audit evidence; validation owns test execution and failures.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE to RUNTIME_OWNED_VALIDATE_PHASE_TASK,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to
    "Invoke bill-boundary-history inline and apply its write/skip rules for the implemented " +
    "runtime change. Emit a bounded history_result containing changed_paths and decisions_recorded " +
    "alongside whether history was written or skipped; do not forward implementation or validation reports.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH to
    "Run no git command in this phase. The runtime stages, commits, and pushes the subtask on the " +
    "resolved feature branch from what you emit here. Emit commit_push_result with `message` (the " +
    "commit subject describing the implemented, reviewed, audited, validated, and history-updated " +
    "outcome) and `changed_paths` (every implementation path this subtask touched, enumerated; the " +
    "runtime stages exactly this set). A missing or blank `message` blocks the subtask rather than " +
    "publishing a provisional subject. Do not emit commit_sha: the runtime captures it after the " +
    "commit. If goal-continuation suppresses PR, this successful phase is the terminal success " +
    "signal for the goal subtask.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR to
    "Invoke bill-pr-description, honor any repo-native PR template, create or reuse the open " +
    "pull request for the branch idempotently, and emit pr_result with the PR URL/number, " +
    "title, and whether a new PR was created.",
)

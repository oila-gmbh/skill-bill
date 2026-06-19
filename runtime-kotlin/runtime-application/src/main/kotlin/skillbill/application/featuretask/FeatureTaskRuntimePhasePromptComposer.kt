package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.workflow.model.SpecSource
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize

/**
 * Pure composer of the full prompt a feature-task-runtime phase agent receives. The persisted
 * per-phase briefing is the durable handoff record; this prompt is the delivered copy of it,
 * framed with the phase task directive and the phase-output contract the schema gate enforces.
 * Without this delivery the agent would receive the default goal-continuation prompt and could
 * never produce schema-valid phase output.
 */
object FeatureTaskRuntimePhasePromptComposer {
  fun compose(
    issueKey: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    suppressDecomposition: Boolean = false,
    parallelReviewAgent: String? = null,
    specSource: SpecSource = SpecSource.LOCAL,
  ): String {
    require(issueKey.isNotBlank()) { "issueKey is required to compose a phase prompt." }
    return listOf(
      header(issueKey, briefing.phaseId),
      ceremonyDirective(briefing),
      mutatingPhaseIdempotencyDirective(briefing.phaseId),
      goalContinuationDirective(briefing.phaseId, suppressDecomposition),
      parallelReviewDirective(briefing.phaseId, parallelReviewAgent),
      commitExclusionDirective(briefing.phaseId, issueKey, specSource),
      briefing.briefingText,
      outputContract(briefing.phaseId),
    ).filter(String::isNotBlank).joinToString(separator = "\n\n")
  }

  private fun header(issueKey: String, phaseId: String): String {
    val label = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepLabels[phaseId] ?: phaseId
    val directive = phaseDirectives[phaseId] ?: error("No phase directive for runtime phase '$phaseId'.")
    return """
      You are executing exactly one phase of the EXPERIMENTAL skill-bill feature-task-runtime
      loop (preplan -> plan -> implement -> review -> audit -> validate -> write_history -> commit_push -> pr)
      for issue $issueKey. The runtime owns the loop; do not run other phases, do not open
      or continue any other skill-bill workflow, and do not call `skill-bill workflow continue`.

      Phase: $phaseId ($label)
      Task: $directive
    """.trimIndent()
  }

  private fun ceremonyDirective(briefing: FeatureTaskRuntimePhaseLaunchBriefing): String {
    val featureSize = FeatureTaskRuntimeFeatureSize.fromWire(briefing.featureSize)
    val scaling = FeatureTaskRuntimePhaseWorkflowDefinition.ceremonyScaling(featureSize)
    val phaseSpecific = when (briefing.phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN ->
        "Apply ${scaling.preplanCeremony.promptLabel}. Keep the gate real: identify concrete scope, " +
          "affected boundaries, risks, and unknowns at the requested depth."
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
        "Apply ${scaling.reviewScope.promptLabel}. Keep the review gate real: inspect the implemented " +
          "change for defects and report concrete file references."
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT ->
        "Apply ${scaling.auditCeremony.promptLabel}. Keep the audit gate real: verify acceptance " +
          "criteria and report concrete gaps."
      else ->
        "Use the resolved feature size for ceremony expectations; all runtime gates remain mandatory."
    }
    return """
      ## Runtime ceremony scaling
      feature_size: ${featureSize.name}
      preplan_ceremony: ${scaling.preplanCeremony.wireValue}
      review_scope: ${scaling.reviewScope.wireValue}
      audit_ceremony: ${scaling.auditCeremony.wireValue}
      $phaseSpecific
      Scaling changes scope and verbosity only; it must not skip or weaken review, audit, validation,
      schema, branch, history, commit, or PR gates.
    """.trimIndent()
  }

  private fun outputContract(phaseId: String): String = """
    ## Required final output (validated schema gate)
    End your response with exactly one JSON object as the last thing you emit. Prefer a raw
    object with nothing after it; a single ```json fenced block is also accepted. The runtime
    extracts that object and blocks the run if it does not validate against the phase-output
    contract:
    - "contract_version": must be exactly "${FeatureTaskRuntimePhaseWorkflowDefinition.definition.contractVersion}"
    - "phase_id": must be "$phaseId"
    - "status": one of "completed", "blocked", "failed"
    - "summary": non-empty string describing what this phase did
    - "produced_outputs": object with at least one entry carrying this phase's concrete
      result for downstream phases (for example plan steps, changed files, findings, or
      validation results)${mutatingPhaseOutputContractAddendum(phaseId)}
    - "derived_notes": optional; when present, a non-empty string of notes for downstream
      phases
    No other top-level fields are allowed.
  """.trimIndent()

  // Mutating phases must prove they reconciled the tree rather than silently skipping work, so the
  // runtime can verify the idempotency contract rather than assume it. Emits only for mutating phases;
  // every other phase's output contract stays byte-for-byte unchanged.
  private fun mutatingPhaseOutputContractAddendum(phaseId: String): String {
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(phaseId)) {
      return ""
    }
    return "\n    - produced_outputs MUST include a reconciliation report: a \"reconciled_state\" object\n" +
      "      (or a \"reconciled_state\" entry) with \"reconciled\": true and concrete evidence that the\n" +
      "      changed files are at their intended target state. A status of \"completed\" with the\n" +
      "      reconciliation report missing or \"reconciled\" not true fails the schema gate loudly."
  }

  // Emitted only for mutating phases (see [FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase]):
  // implement now, a future implement_fix later. The directive is empty for every other phase so their
  // prompts stay byte-for-byte unchanged.
  private fun mutatingPhaseIdempotencyDirective(phaseId: String): String {
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

  private fun parallelReviewDirective(phaseId: String, parallelReviewAgent: String?): String {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW || parallelReviewAgent.isNullOrBlank()) {
      return ""
    }
    return """
      ## Parallel review lane
      Run the review using `bill-code-review parallel:$parallelReviewAgent` so a second lane from $parallelReviewAgent reviews the same diff concurrently. Findings from both lanes are merged with provenance labels.
    """.trimIndent()
  }

  // Emits only for the commit phase of a linear-mode run: the local spec scratch is never committed
  // (it is rehydrated from Linear on demand and deleted on success), so the commit step must stage by
  // explicit enumeration and exclude the whole `.feature-specs/{KEY}/` tree. For local mode (default)
  // the section is empty, leaving the commit prompt byte-for-byte unchanged (AC6).
  private fun commitExclusionDirective(phaseId: String, issueKey: String, specSource: SpecSource): String {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH || specSource != SpecSource.LINEAR) {
      return ""
    }
    return """
      ## Linear-mode commit exclusion
      This feature's spec_source is `linear`: the local spec scratch is NOT committed. Stage every
      changed path by explicit enumeration and never run `git add -A` / `git add .`. Exclude the
      entire `.feature-specs/$issueKey/` directory from staging — the parent spec, every subtask
      spec, and `decomposition-manifest.yaml`. The committed tree must contain no spec, subtask spec,
      or manifest file. The local spec scratch is deleted on terminal success and rehydrated from
      Linear when a later resume or verify needs it.
    """.trimIndent()
  }

  private fun goalContinuationDirective(phaseId: String, suppressDecomposition: Boolean): String {
    if (!suppressDecomposition || phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN) {
      return ""
    }
    return """
      ## Goal-continuation planning constraint
      This run is already executing one governed decomposed subtask. Do not propose or emit a new
      decomposition package in the plan phase. Produce an implementable single-subtask plan for the
      current spec; `produced_outputs.mode` must not be "decompose".
    """.trimIndent()
  }

  // One imperative task directive per phase; the briefing carries the spec-specific scope.
  private val phaseDirectives: Map<String, String> = mapOf(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to
      "Produce the scaled pre-planning digest for the resolved feature size. Do not modify " +
      "repository files during this phase. Emit a " +
      "schema-valid produced_outputs object containing the digest for the downstream plan phase.",
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to
      "Produce an ordered implementation plan that satisfies every acceptance criterion, using " +
      "the upstream preplan digest as planning context. Do not modify repository files during " +
      "this phase.",
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to
      "Reconcile the repository to the intended state the upstream plan output describes: make the " +
      "changes it specifies, treating any already-applied change as a no-op. See the mutating-phase " +
      "idempotency contract below.",
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to
      "Review the implemented changes at the encoded review scope against the acceptance criteria " +
      "and report defects with concrete file references.",
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to
      "Run the encoded completeness audit ceremony and report any acceptance-criterion gaps.",
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE to
      "Run the repository validation gate relevant to the change. Fix validation findings at " +
      "their root cause and rerun the gate until it passes; validation findings are repair work, " +
      "not a reason to block the phase.",
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to
      "Invoke bill-boundary-history inline and apply its write/skip rules for the implemented " +
      "runtime change. Emit a produced_outputs object containing history_result with whether " +
      "history was written or skipped and the affected path when written.",
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH to
      "Stage and commit the implemented, reviewed, audited, validated, and history-updated " +
      "changes on the resolved feature branch, then push the branch. Stage by explicit enumerated " +
      "path; never run `git add -A` or `git add .`. Emit commit_push_result " +
      "with the commit SHA, branch name, and pushed status. If goal-continuation suppresses PR, " +
      "this successful phase is the terminal success signal for the goal subtask.",
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR to
      "Invoke bill-pr-description, honor any repo-native PR template, create or reuse the open " +
      "pull request for the branch idempotently, and emit pr_result with the PR URL/number, " +
      "title, and whether a new PR was created.",
  )
}

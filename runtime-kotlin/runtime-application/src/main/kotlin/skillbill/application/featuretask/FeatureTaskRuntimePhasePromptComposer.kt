package skillbill.application.featuretask

import skillbill.agentaddon.model.AgentAddonPromptFormatter
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.workflow.model.CodeReviewExecutionMode
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
@Suppress("TooManyFunctions") // one cohesive prompt-composition seam; each function is a named directive
object FeatureTaskRuntimePhasePromptComposer {
  @Suppress("LongParameterList") // one cohesive phase-prompt delivery; bundling these would only hide them
  fun compose(
    issueKey: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    suppressDecomposition: Boolean = false,
    parallelReviewAgent: String? = null,
    codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.DEFAULT,
    reviewPassNumber: Int? = null,
    goalSubtaskReviewInput: GoalSubtaskReviewInput? = null,
    specSource: SpecSource = SpecSource.LOCAL,
    priorSchemaFailure: String? = null,
    specReference: String? = null,
    agentAddonSelection: HydratedAgentAddonSelection = HydratedAgentAddonSelection(),
  ): String {
    require(issueKey.isNotBlank()) { "issueKey is required to compose a phase prompt." }
    return listOf(
      header(issueKey, briefing.phaseId),
      ceremonyDirective(briefing, reviewPassNumber),
      mutatingPhaseIdempotencyDirective(briefing.phaseId),
      goalContinuationDirective(briefing.phaseId, suppressDecomposition),
      reviewExecutionDirective(
        briefing.phaseId,
        codeReviewMode,
        reviewPassNumber,
        parallelReviewAgent,
        goalSubtaskReviewInput,
      ),
      commitExclusionDirective(briefing.phaseId, issueKey, specSource),
      specCommitInclusionDirective(briefing.phaseId, specReference, specSource),
      AgentAddonPromptFormatter.format(agentAddonSelection),
      briefing.briefingText,
      retryCorrectionDirective(briefing, priorSchemaFailure),
      outputContract(briefing),
    ).filter(String::isNotBlank).joinToString(separator = "\n\n")
  }

  // Emitted only when the prior attempt at this phase failed the schema gate (its reason threaded in by
  // the runner's fix loop). A schema-gate retry that relaunches the byte-for-byte-identical prompt is a
  // blind re-roll: the agent never learns why it was rejected and tends to repeat the same miss (e.g. an
  // audit emitting a prose verdict table instead of the required structured signal). Surfacing the
  // validator's reason turns each retry into a corrective attempt. Empty on the first attempt, so a
  // forward launch's prompt stays byte-for-byte unchanged.
  private fun retryCorrectionDirective(
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    priorSchemaFailure: String?,
  ): String {
    if (priorSchemaFailure.isNullOrBlank()) {
      return ""
    }
    val base = """
      ## Previous attempt was REJECTED by the schema gate — correct it now
      Your previous attempt at this phase did not produce schema-valid output and was rejected. Reason:
      $priorSchemaFailure
      Re-read the required-final-output contract below and emit exactly one schema-valid JSON object that
      carries the missing signal. Do not repeat the same mistake; prose alone does not satisfy the gate.
    """.trimIndent()
    val remediationCorrection = if (briefing.auditRepairItemIds.isEmpty()) {
      ""
    } else {
      "\nCorrect every carried item exactly once and in this order: " +
        briefing.auditRepairItemIds.joinToString() + ".\n" +
        auditRemediationOutputExample(briefing.auditRepairItemIds)
    }
    val dispositionCorrection = if (briefing.unresolvedAuditGapIds.isEmpty()) {
      ""
    } else {
      "\nDisposition every durable unresolved gap exactly once in this order: " +
        briefing.unresolvedAuditGapIds.joinToString() + ".\n" +
        priorGapDispositionsExample(briefing.unresolvedAuditGapIds)
    }
    return base + remediationCorrection + dispositionCorrection +
      unparseableRootCorrection(briefing, priorSchemaFailure)
  }

  // The `<root> must be an object` / malformed-output failures mean the runtime could not extract ANY
  // JSON object from the response — the agent answered with a prose Markdown table, a bare array, or an
  // empty body. Echoing that validator reason alone is what lets a verifying phase (audit especially)
  // burn its whole fix loop re-emitting the same prose: it reads "must be an object" and assumes its
  // table was the object. This appends the concrete correction — name the likely mistake and hand back a
  // minimal fill-in skeleton for this phase. Empty for field-level violations, where the reason already
  // pinpoints the offending field, so those retries stay byte-for-byte unchanged.
  private fun unparseableRootCorrection(
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    priorSchemaFailure: String,
  ): String {
    val rootNotParseable = priorSchemaFailure.contains("<root> must be an object") ||
      priorSchemaFailure.contains("Phase output is malformed")
    if (!rootNotParseable) {
      return ""
    }
    return "\nThe runtime could NOT parse a single JSON object out of your previous output — you likely " +
      "answered\nwith prose, a Markdown table, or a JSON array. None of those can advance the gate. Emit " +
      "exactly ONE\nJSON object as the final thing in your response — no array wrapper and no leading " +
      "table — matching\nthis skeleton with real values:\n" + retrySkeleton(briefing)
  }

  // A minimal, phase-correct object the agent can fill in. Built line-by-line (the optional verdict line
  // is omitted for non-verifying phases) so the emitted skeleton is always syntactically valid JSON with
  // no dangling comma, and so verifying phases see the exact verdict and produced_outputs keys the gate
  // reads back.
  private fun retrySkeleton(briefing: FeatureTaskRuntimePhaseLaunchBriefing): String = buildList {
    val phaseId = briefing.phaseId
    add("```json")
    add("{")
    add("  \"contract_version\": \"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION\",")
    add("  \"phase_id\": \"$phaseId\",")
    add("  \"status\": \"completed\",")
    verdictSkeletonLine(phaseId)?.let(::add)
    add("  \"summary\": \"<one sentence describing what this phase did>\",")
    add("  \"produced_outputs\": { ${producedOutputsSkeletonEntry(briefing)} }")
    add("}")
    add("```")
  }.joinToString(separator = "\n")

  private fun verdictSkeletonLine(phaseId: String): String? {
    val verdict = FeatureTaskRuntimeVerificationSignalKeys.VERDICT
    return when (phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT -> "  \"$verdict\": \"satisfied\","
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> "  \"$verdict\": \"approved\","
      else -> null
    }
  }

  private fun producedOutputsSkeletonEntry(briefing: FeatureTaskRuntimePhaseLaunchBriefing): String =
    when (briefing.phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT ->
        "\"${FeatureTaskRuntimeVerificationSignalKeys.AUDIT_UNMET_CRITERIA}\": []"
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
        "\"${FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS}\": []"
      else -> if (briefing.auditRepairItemIds.isEmpty()) {
        "\"result\": \"<concrete output for downstream phases>\""
      } else {
        "\"reconciled_state\": { \"reconciled\": true, \"evidence\": \"<verified end state>\" }, " +
          "\"repair_item_results\": ${repairItemResultsJson(briefing.auditRepairItemIds)}"
      }
    }

  // The forward phase order as prose, derived from the single topology source so the briefing can
  // never drift from the graph the runtime actually drives. Loop-only phases are excluded because a
  // clean run never launches them.
  private val forwardPhaseOrder: String =
    FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds
      .filterNot { it in FeatureTaskRuntimePhaseWorkflowDefinition.transitions.loopOnlyPhaseIds }
      .joinToString(" -> ")

  private fun header(issueKey: String, phaseId: String): String {
    val label = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepLabels[phaseId] ?: phaseId
    val directive = phaseDirectives[phaseId] ?: error("No phase directive for runtime phase '$phaseId'.")
    return """
      You are executing exactly one phase of the EXPERIMENTAL skill-bill feature-task-runtime
      loop ($forwardPhaseOrder)
      for issue $issueKey. The runtime owns the loop; do not run other phases, do not open
      or continue any other skill-bill workflow, and do not call `skill-bill workflow continue`.

      Phase: $phaseId ($label)
      Task: $directive
    """.trimIndent()
  }

  private fun ceremonyDirective(briefing: FeatureTaskRuntimePhaseLaunchBriefing, reviewPassNumber: Int?): String {
    val featureSize = FeatureTaskRuntimeFeatureSize.fromWire(briefing.featureSize)
    val scaling = FeatureTaskRuntimePhaseWorkflowDefinition.ceremonyScaling(featureSize)
    val remediationReview = briefing.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
      reviewPassNumber == 2
    val reviewScope = if (remediationReview) "remediation_delta" else scaling.reviewScope.wireValue
    val phaseSpecific = when (briefing.phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN ->
        "Apply ${scaling.preplanCeremony.promptLabel}. Keep the gate real: identify concrete scope, " +
          "affected boundaries, risks, and unknowns at the requested depth."
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> if (remediationReview) {
        "Apply bill-code-review mode:inline context:feature-remediation to only the combined staged, unstaged, " +
          "and untracked remediation delta since checkpoint HEAD. Do not expand the re-review to the full " +
          "feature branch."
      } else {
        "Apply ${scaling.reviewScope.promptLabel}. Keep the review gate real: inspect the implemented " +
          "change for defects and report concrete file references."
      }
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
      review_scope: $reviewScope
      audit_ceremony: ${scaling.auditCeremony.wireValue}
      $phaseSpecific
      Scaling changes scope and verbosity only; it must not skip or weaken review, audit, validation,
      schema, branch, history, commit, or PR gates.
    """.trimIndent()
  }

  private fun outputContract(briefing: FeatureTaskRuntimePhaseLaunchBriefing): String {
    val phaseId = briefing.phaseId
    return """
    ## Required final output (validated schema gate)
    End your response with exactly one JSON object as the last thing you emit. Prefer a raw
    object with nothing after it; a single ```json fenced block is also accepted. The runtime
    extracts that object and blocks the run if it does not validate against the phase-output
    contract:
    - "contract_version": must be exactly "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION"
    - "phase_id": must be "$phaseId"
    - "status": one of "completed", "blocked", "failed"
    - "failure_disposition": required by the runtime when status is "blocked" or "failed"; one of
      "retryable", "non_retryable_policy_conflict", "needs_user_action", "process_failure", or
      "invalid_output". Omit it when status is "completed".
    - "summary": non-empty string describing what this phase did
    - "produced_outputs": object with at least one entry carrying this phase's concrete
      result for downstream phases (for example plan steps, changed files, findings, or
      validation results)${producedOutputsAddendum(briefing)}
    - "derived_notes": optional; when present, a non-empty string of notes for downstream
      phases
    - "verdict": optional top-level string; verifying phases (review, audit) set it to drive the
      advance-vs-remediation decision — see the verifying-phase signal above
    No top-level fields other than the ones listed above are allowed.
    """.trimIndent()
  }

  // Phase-specific addendum to the produced_outputs bullet. Mutating phases (implement, implement_fix)
  // must prove they reconciled the tree to target rather than silently skipping work, so the runtime can
  // verify the idempotency contract rather than assume it. Verifying phases (review, audit) gate on a
  // machine-readable signal, not prose: naming the exact field the gate keys on is what prevents a
  // thorough agent from delivering its verdict as a prose Markdown table the gate cannot read (and then
  // blocking after a blind retry loop). The two phase sets are disjoint, so at most one branch is ever
  // non-empty; every other phase returns "" so its output contract stays byte-for-byte unchanged.
  private fun producedOutputsAddendum(briefing: FeatureTaskRuntimePhaseLaunchBriefing): String {
    val phaseId = briefing.phaseId
    if (FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(phaseId)) {
      val remediation = if (briefing.auditRepairItemIds.isEmpty()) {
        ""
      } else {
        "\n    - This is AUDIT-GAP REMEDIATION. produced_outputs MUST also include repair_item_results " +
          "with exactly these ids in order: ${briefing.auditRepairItemIds.joinToString()}. Every result must " +
          "contain only repair_item_id, outcome (fixed or already_satisfied), non-empty " +
          "changed_paths_or_symbols, non-empty executed_verification, and structured result_evidence " +
          "with observation, artifact_ref, and check_ref. check_ref MUST be AC-###, F-###, or a single " +
          "name ending in Test or Check (optionally followed by :symbol); do not put a command, result, " +
          "sentence, spaces, or shell punctuation in check_ref.\n" +
          auditRemediationOutputExample(briefing.auditRepairItemIds)
      }
      return "\n    - produced_outputs MUST include a reconciliation report: a \"reconciled_state\" object\n" +
        "      (or a \"reconciled_state\" entry) with \"reconciled\": true and concrete evidence that the\n" +
        "      changed files are at their intended target state. A status of \"completed\" with the\n" +
        "      reconciliation report missing or \"reconciled\" not true fails the schema gate loudly." + remediation
    }
    val findings = FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS
    val unmetCriteria = FeatureTaskRuntimeVerificationSignalKeys.AUDIT_UNMET_CRITERIA
    val verdict = FeatureTaskRuntimeVerificationSignalKeys.VERDICT
    return when (phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
        "\n    - This is a VERIFYING phase: produced_outputs MUST carry a \"$findings\" array (each entry a\n" +
          "      severity/message object; an explicit empty [] affirms no Blocker findings) AND/OR a\n" +
          "      top-level \"$verdict\" of \"approved\" or \"changes_requested\". Output carrying NEITHER signal\n" +
          "      fails the schema gate loudly — a prose summary alone cannot advance the gate."
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT ->
        "\n    - This is a VERIFYING phase: produced_outputs MUST carry an \"$unmetCriteria\" array (one\n" +
          "      message per unmet acceptance criterion; an explicit empty [] affirms every criterion is met)\n" +
          "      AND/OR a top-level \"$verdict\" of \"satisfied\" or \"gaps_found\". Output carrying NEITHER signal\n" +
          "      fails the schema gate loudly — a prose verdict (e.g. a Markdown table) cannot advance the gate.\n" +
          "      A gaps_found result MUST include one schema-valid audit_repair_plan covering every entry in\n" +
          "      unmet_criteria; use concrete production references such as src/main/Example.kt:Example.\n" +
          "      TEST EXCLUSION: missing tests, weak tests, incomplete test coverage, unrealistic fixtures,\n" +
          "      insufficient assertions, and any other test-only concern are NEVER audit gaps. Do not inspect\n" +
          "      or assess test adequacy, cite test files as an affected boundary, or create repair items that\n" +
          "      add or change tests. Validation owns test execution and failures. Audit may report only a\n" +
          "      concrete defect in production behavior or production implementation; when no such defect is\n" +
          "      evidenced, emit satisfied even if test coverage is absent or inadequate." +
          auditDispositionAddendum(briefing.unresolvedAuditGapIds)
      else -> ""
    }
  }

  private fun auditRemediationOutputExample(repairItemIds: List<String>): String =
    "Required produced_outputs shape:\n```json\n{\n" +
      "  \"reconciled_state\": { \"reconciled\": true, \"evidence\": \"<verified end state>\" },\n" +
      "  \"repair_item_results\": ${repairItemResultsJson(repairItemIds)}\n" +
      "}\n```"

  private fun auditDispositionAddendum(gapIds: List<String>): String = if (gapIds.isEmpty()) {
    ""
  } else {
    "\n      This follow-up audit MUST include produced_outputs.prior_gap_dispositions with exactly these " +
      "gap ids in order: ${gapIds.joinToString()}. Each entry contains only gap_id, status " +
      "(resolved or recurring), and evidence with observation (resolution_verified or recurrence_verified), " +
      "artifact_ref, and check_ref.\n" + priorGapDispositionsExample(gapIds)
  }

  private fun priorGapDispositionsExample(gapIds: List<String>): String =
    "Required prior_gap_dispositions shape:\n```json\n" + gapIds.joinToString(
      prefix = "[",
      postfix = "]\n```",
      separator = ", ",
    ) { gapId ->
      "{ \"gap_id\": \"$gapId\", \"status\": \"resolved\", \"evidence\": { " +
        "\"observation\": \"resolution_verified\", \"artifact_ref\": \"src/main/Example.kt:Example\", " +
        "\"check_ref\": \"ExampleTest\" } }"
    }

  private fun repairItemResultsJson(repairItemIds: List<String>): String = repairItemIds.joinToString(
    prefix = "[",
    postfix = "]",
    separator = ", ",
  ) { repairItemId ->
    "{ \"repair_item_id\": \"$repairItemId\", \"outcome\": \"fixed\", " +
      "\"changed_paths_or_symbols\": [\"<path or symbol>\"], " +
      "\"executed_verification\": [\"<command and result>\"], " +
      "\"result_evidence\": { \"observation\": \"fix_verified\", " +
      "\"artifact_ref\": \"src/main/Example.kt:Example\", \"check_ref\": \"ExampleTest\" } }"
  }

  // Emitted only for mutating phases (see [FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase]):
  // implement and implement_fix. The directive is empty for every other phase so their prompts stay
  // byte-for-byte unchanged.
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

  private fun reviewExecutionDirective(
    phaseId: String,
    codeReviewMode: CodeReviewExecutionMode,
    reviewPassNumber: Int?,
    parallelReviewAgent: String?,
    goalSubtaskReviewInput: GoalSubtaskReviewInput?,
  ): String {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) {
      return ""
    }
    val parallel = parallelReviewAgent?.takeIf(String::isNotBlank)?.let { agent ->
      " Combine it with `parallel:$agent`; both lanes must receive mode:${codeReviewMode.wireValue} " +
        "and the second lane must not launch parallel review recursively."
    }.orEmpty()
    val goalScope = goalSubtaskReviewInput?.let { input ->
      """

      ## Goal subtask review scope
      Review only this child-owned delta from durable base `${input.reviewBaseSha}` to current HEAD `${input.currentHeadSha}`.
      It includes committed, staged, unstaged, and owned untracked changes below.
      Do not use `origin/main...HEAD`, a merge base, the full feature branch, or a replacement baseline.
      If parallel CLI delegation is required, give both lanes this exact diff through `--diff-file`;
      never select a branch scope.

      ${input.reviewText}
      """.trimIndent()
    }.orEmpty()
    val remediationScope = if (reviewPassNumber == 2 && goalSubtaskReviewInput == null) {
      " Review only the combined working-tree remediation delta since checkpoint HEAD; include staged, unstaged, " +
        "and untracked changes, and do not use the full branch diff."
    } else {
      ""
    }
    return """
      ## Review execution mode
      Run `bill-code-review mode:${codeReviewMode.wireValue}` for this review. The initial pass uses the run-selected
      mode; every later pass is explicitly INLINE under context:feature-remediation. Never launch a third review pass.
      AUTO keeps the shared policy's existing selection; remediation INLINE uses the governed exception and selects
      inline specialist coverage for high-risk signals; DELEGATED must use normal routed delegation and fail if workers
      cannot start.$parallel$goalScope$remediationScope
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

  // Emits only for the commit phase of a local-mode run when a spec reference is known: the runtime
  // updates the spec file with the run's completion status just before launching commit_push, so the
  // agent must include it in the staged files. Empty for linear mode (spec is excluded from the commit
  // there) and for all other phases, leaving those prompts byte-for-byte unchanged.
  private fun specCommitInclusionDirective(phaseId: String, specReference: String?, specSource: SpecSource): String {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH ||
      specSource != SpecSource.LOCAL ||
      specReference.isNullOrBlank()
    ) {
      return ""
    }
    return """
      ## Spec file — stage with this commit
      The runtime updated `$specReference` with the run's completion status just before this
      phase was launched. Stage it together with the other changed files.
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
      "idempotency contract below. When the briefing carries audit_gaps, reuse its immutable initial " +
      "preplan and plan outputs and change only what the latest listed gaps require; do not regenerate " +
      "planning, expand scope, or disturb settled implementation.",
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX to
      "Address the carried review Blocker findings on the CURRENT working tree as incremental " +
      "reconciliation: fix exactly those findings using the review findings, the latest implement " +
      "output, and the intended state from the briefing. Do NOT re-apply the plan from scratch and do " +
      "not expand scope beyond the findings. Treat any fix already present as a no-op. See the " +
      "mutating-phase idempotency contract below.",
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to
      "Review the implemented changes at the encoded review scope against the acceptance criteria " +
      "and report defects with concrete file references.",
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to
      "Run the encoded completeness audit ceremony and report production-behavior or production-implementation " +
      "acceptance-criterion gaps only. Never report test adequacy, coverage, fixtures, assertions, or other " +
      "test-only concerns as audit gaps.",
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

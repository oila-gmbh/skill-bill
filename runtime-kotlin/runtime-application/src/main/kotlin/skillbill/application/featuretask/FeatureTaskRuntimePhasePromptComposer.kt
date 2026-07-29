package skillbill.application.featuretask

import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.review.model.ReviewIssueCategory
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.model.SpecSource
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewFinding
import skillbill.workflow.taskruntime.model.MAX_AUDIT_REPAIR_REF_LENGTH

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
    resolvedReviewTier: CodeReviewExecutionMode? = null,
    reviewDecidingRule: String? = null,
    priorBlockerFindingIds: List<String> = emptyList(),
    carriedBlockerFindings: List<GoalSubtaskReviewFinding> = emptyList(),
    specSource: SpecSource = SpecSource.LOCAL,
    priorSchemaFailure: String? = null,
    operatorBlockRetry: FeatureTaskRuntimeOperatorBlockRetry? = null,
    specReference: String? = null,
  ): String {
    require(issueKey.isNotBlank()) { "issueKey is required to compose a phase prompt." }
    val resolvedReviewMode = resolvedReviewTier ?: codeReviewMode
    return listOf(
      header(issueKey, briefing.phaseId),
      ceremonyDirective(briefing, reviewPassNumber, resolvedReviewMode),
      mutatingPhaseIdempotencyDirective(briefing.phaseId),
      goalContinuationDirective(briefing.phaseId, suppressDecomposition),
      reviewExecutionDirective(
        briefing.phaseId,
        ReviewExecutionDirectiveInputs(
          codeReviewMode = codeReviewMode,
          parallelReviewAgent = parallelReviewAgent,
          goalSubtaskReviewInput = goalSubtaskReviewInput,
          reviewPassNumber = reviewPassNumber,
          resolvedReviewTier = resolvedReviewTier,
          reviewDecidingRule = reviewDecidingRule,
        ),
      ),
      commitExclusionDirective(briefing.phaseId, issueKey, specSource),
      specCommitInclusionDirective(briefing.phaseId, specReference, specSource),
      briefing.briefingText,
      operatorBlockRetryDirective(briefing.phaseId, operatorBlockRetry),
      retryCorrectionDirective(briefing, priorSchemaFailure),
      outputContract(briefing, priorBlockerFindingIds, carriedBlockerFindings),
    ).filter(String::isNotBlank).joinToString(separator = "\n\n")
  }

  /**
   * Applies the add-on content budget before hydrated content reaches the prompt.
   *
   * The declared consumer assignment is manifest-owned `feature_addon_usage.feature-task`, which
   * scopes add-ons to a feature-task run as a whole — every phase of this composer's run is that
   * consumer, so there is no narrower per-phase assignment to honor here. What this seam adds is the
   * budget: it is deliberately separate from the per-projection phase-receipt budget, so neither can
   * consume the other's headroom and an oversized add-on rejects with a typed error rather than
   * silently inflating the briefing.
   */
  internal fun budgetedAddonsFor(
    phaseId: String,
    selection: HydratedAgentAddonSelection,
  ): HydratedAgentAddonSelection {
    val budget = FeatureTaskRuntimeHandoffProjectionBudget.ADDON_CONTENT
    if (selection.entries.size > budget.maxCollectionItems) {
      throw InvalidFeatureTaskRuntimeHandoffProjectionError(
        workflowId = null,
        consumerPhaseId = phaseId,
        projectionName = ADDON_CONTENT_PROJECTION_NAME,
        projectionContractId = ADDON_CONTENT_CONTRACT_ID,
        projectionContractVersion = ADDON_CONTENT_CONTRACT_VERSION,
        failureKind = FeatureTaskRuntimeHandoffProjectionFailureKind.BUDGET_OVERFLOW,
        reason = "${selection.entries.size} hydrated add-ons exceed the ${budget.maxCollectionItems}-item " +
          "add-on budget; the runtime rejects rather than dropping add-ons.",
      )
    }
    val totalBytes = selection.entries.sumOf { it.content.toByteArray(Charsets.UTF_8).size }
    if (totalBytes > budget.maxUtf8Bytes) {
      throw InvalidFeatureTaskRuntimeHandoffProjectionError(
        workflowId = null,
        consumerPhaseId = phaseId,
        projectionName = ADDON_CONTENT_PROJECTION_NAME,
        projectionContractId = ADDON_CONTENT_CONTRACT_ID,
        projectionContractVersion = ADDON_CONTENT_CONTRACT_VERSION,
        failureKind = FeatureTaskRuntimeHandoffProjectionFailureKind.BUDGET_OVERFLOW,
        reason = "hydrated add-on content is $totalBytes UTF-8 bytes against the ${budget.maxUtf8Bytes}-byte " +
          "add-on budget, which is counted independently of the phase-receipt budget; the runtime rejects " +
          "rather than truncating add-on content.",
      )
    }
    return selection
  }

  internal const val ADDON_CONTENT_PROJECTION_NAME: String = "agent_addon_content"
  private const val ADDON_CONTENT_CONTRACT_ID: String = "feature_task_runtime.agent_addon_content"
  private const val ADDON_CONTENT_CONTRACT_VERSION: String = "0.1"

  private fun operatorBlockRetryDirective(phaseId: String, retry: FeatureTaskRuntimeOperatorBlockRetry?): String {
    if (retry == null) return ""
    require(retry.phaseId == phaseId) {
      "Operator blocked-phase retry guidance for '${retry.phaseId}' cannot be delivered to phase '$phaseId'."
    }
    return """
      ## Operator-applied blocked-phase retry decision
      An operator reviewed the prior block and explicitly reopened this phase. Apply this decision:
      ${retry.reason}
      Re-evaluate the current repository state using this decision. Do not repeat the superseded block solely
      because of the prior interpretation. The governed acceptance criteria and output contract still apply.
    """.trimIndent()
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
    if (briefing.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
      priorSchemaFailure.startsWith("Implementation incomplete;")
    ) {
      return """
        ## Continue the implementation
        The prior implementation receipt was schema-valid but did not close the authoritative plan.
        Continue implementation from this complete bounded durable receipt; do not treat this as schema correction:
        $priorSchemaFailure
        Reconcile the current tree to the remaining plan obligations and emit the next bounded receipt.
      """.trimIndent()
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
    return base + remediationCorrection +
      unparseableRootCorrection(briefing, priorSchemaFailure) +
      boundedReferenceCorrection(priorSchemaFailure)
  }

  private fun boundedReferenceCorrection(priorSchemaFailure: String): String {
    val field = when {
      priorSchemaFailure.contains("artifact_ref") -> "artifact_ref"
      priorSchemaFailure.contains("check_ref") -> "check_ref"
      else -> return ""
    }
    val reportsLengthViolation = priorSchemaFailure.contains("must be at most") ||
      priorSchemaFailure.contains("allows at most") ||
      priorSchemaFailure.contains("maxLength")
    if (!reportsLengthViolation) {
      return ""
    }
    val replacement = if (field == "artifact_ref") {
      "one repository-relative path, optionally followed by one :symbol, such as " +
        "runtime-kotlin/runtime-mcp/src/test/kotlin/skillbill/mcp/McpStdioServerTest.kt"
    } else {
      "one acceptance-criterion, finding, test, or check identifier, such as AC-005 or McpStdioServerTest"
    }
    return """

      The rejected $field is a bounded pointer, not an evidence container. Replace it with $replacement.
      It MUST be at most $MAX_AUDIT_REPAIR_REF_LENGTH characters. Do not concatenate multiple paths,
      symbols, findings, commands, or explanations into this field. Put necessary detail in the issue,
      fix, or other schema-authorized descriptive fields.
    """.trimIndent()
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
        "\"${FeatureTaskRuntimeVerificationSignalKeys.AUDIT_GAPS}\": [], " +
          "\"${FeatureTaskRuntimeVerificationSignalKeys.AUDIT_NON_BLOCKING_FINDINGS}\": []"
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

  private fun ceremonyDirective(
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    reviewPassNumber: Int?,
    resolvedTier: CodeReviewExecutionMode,
  ): String {
    val featureSize = FeatureTaskRuntimeFeatureSize.fromWire(briefing.featureSize)
    val scaling = FeatureTaskRuntimePhaseWorkflowDefinition.ceremonyScaling(featureSize)
    val remediationReview = briefing.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
      reviewPassNumber == 2
    val reviewScope = scaling.reviewScope.wireValue
    val phaseSpecific = when (briefing.phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN ->
        "Apply ${scaling.preplanCeremony.promptLabel}. Keep the gate real: identify concrete scope, " +
          "affected boundaries, risks, and unknowns at the requested depth."
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> if (remediationReview) {
        // The mode token is derived, never hardcoded: rendering mode:inline under a pinned delegated
        // run would emit a mode/context pairing the governed skill rejects.
        "Apply bill-code-review mode:${remediationModeToken(resolvedTier)} context:feature-remediation, " +
          "bounded to the remediation delta: the prior pass's Blocker findings union " +
          "diff(pre-fix tree -> post-fix tree). Do not re-review the subtask's full base-to-current delta."
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

  // context:feature-remediation is valid only with mode:inline, so a run pinned to delegated still
  // renders inline for the reserved pass rather than emitting a pairing the governed skill rejects.
  private fun remediationModeToken(resolvedTier: CodeReviewExecutionMode): String =
    CodeReviewExecutionMode.INLINE.wireValue.takeIf {
      resolvedTier != CodeReviewExecutionMode.INLINE
    } ?: resolvedTier.wireValue

  private fun outputContract(
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    priorBlockerFindingIds: List<String>,
    carriedBlockerFindings: List<GoalSubtaskReviewFinding>,
  ): String {
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
      validation results)${producedOutputsAddendum(
      briefing,
    )}${dispositionAddendum(briefing, priorBlockerFindingIds, carriedBlockerFindings)}
    - "derived_notes": optional; when present, a non-empty string of notes for downstream
      phases
    - "verdict": optional top-level string; verifying phases (review, audit) set it to drive the
      advance-vs-remediation decision — see the verifying-phase signal above
    No top-level fields other than the ones listed above are allowed.
    """.trimIndent()
  }

  /**
   * The only seam that instructs the reserved remediation pass to emit
   * `produced_outputs.blocker_dispositions`. Without it the producer key is never written and the
   * disposition path — the terminating signal for the bounded remediation loop — is unreachable in
   * production. The prior pass's Blocker finding ids are supplied so the agent keys its entries
   * against real ids instead of inventing them, and a disposition is required for every one of them.
   * Empty for pass one and for every non-review phase, so those prompts stay byte-for-byte unchanged.
   */
  private fun dispositionAddendum(
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    priorBlockerFindingIds: List<String>,
    carriedBlockerFindings: List<GoalSubtaskReviewFinding>,
  ): String {
    if (briefing.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) {
      return ""
    }
    if (priorBlockerFindingIds.isEmpty()) {
      return "\n    - The prior review pass emitted no Blocker, so no disposition is required: emit\n" +
        "      produced_outputs.blocker_dispositions as an explicit []."
    }
    val example = priorBlockerFindingIds.joinToString(prefix = "[", postfix = "]", separator = ", ") { findingId ->
      "{ \"finding_id\": \"$findingId\", \"verdict\": \"resolved\", " +
        "\"evidence\": [\"checkpoint=<active repository fingerprint>;location=<path>:<line>\"] }"
    }
    val carried = carriedBlockerFindings.joinToString(separator = "\n") {
      "      - ${it.findingId}: severity=${it.severity}; category=${it.category}; " +
        "location=${it.location}; summary=${it.summary}; source_generation=${it.sourceGenerationId}"
    }
    return "\n    - This is the RESERVED REMEDIATION PASS. produced_outputs MUST carry a\n" +
      "      \"blocker_dispositions\" array with EXACTLY ONE entry for EVERY Blocker the prior pass\n" +
      "      emitted — these ids, all of them, no more and no fewer:\n" +
      "      ${priorBlockerFindingIds.joinToString()}.\n" +
      "      Durable carried Blockers requiring re-verification:\n$carried\n" +
      "      Each entry contains finding_id, verdict (exactly one of resolved, unresolved, superseded),\n" +
      "      and a non-empty evidence array bound to the active repository fingerprint and citing the\n" +
      "      repository-relative changed lines that resolve or fail to\n" +
      "      resolve it. An unevidenced disposition is rejected at the parse seam. A short list that\n" +
      "      omits any prior Blocker id is rejected. Major findings are out of disposition scope.\n" +
      "      ```json\n" +
      "      { \"blocker_dispositions\": $example }\n" +
      "      ```"
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
          "with observation, artifact_ref, and check_ref. observation MUST be exactly one of " +
          "required_behavior_absent, verification_failed, contract_rejected, state_mismatch, fix_verified, " +
          "already_satisfied_verified, resolution_verified, or recurrence_verified; use " +
          "fix_verified when outcome is fixed and already_satisfied_verified when outcome is " +
          "already_satisfied. recurrence_verified is for recurring audit-gap disposition evidence, not a " +
          "repair_item_result whose outcome is fixed. Do not invent a synonym outside this list. " +
          "artifact_ref MUST be a repository-relative path " +
          "optionally followed by one :symbol; do not put a sentence, spaces, test description, command, " +
          "result, or additional prose in artifact_ref. check_ref MUST be AC-###, F-###, or a single " +
          "name ending in Test or Check (optionally followed by :symbol); do not put a command, result, " +
          "sentence, spaces, or shell punctuation in check_ref.\n" +
          "    - produced_outputs MUST include deferred_repair_item_ids. Completed remediation uses []; " +
          "blocked remediation lists every remaining item, while unresolvable_repair identifies exactly one.\n" +
          auditRemediationOutputExample(briefing.auditRepairItemIds)
      }
      return "\n    - produced_outputs MUST include a reconciliation report: a \"reconciled_state\" object\n" +
        "      (or a \"reconciled_state\" entry) with \"reconciled\": true and concrete evidence that the\n" +
        "      changed files are at their intended target state. A status of \"completed\" with the\n" +
        "      reconciliation report missing or \"reconciled\" not true fails the schema gate loudly." +
        planningProjectionShapeExampleFor(phaseId) + remediation
    }
    val findings = FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS
    val verdict = FeatureTaskRuntimeVerificationSignalKeys.VERDICT
    return when (phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
      -> planningProjectionShapeExampleFor(phaseId)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
        "\n    - This is a VERIFYING phase: produced_outputs MUST carry a \"$findings\" array (each entry a\n" +
          "      severity/message object; an explicit empty [] affirms no Blocker or Major findings) AND/OR a\n" +
          "      top-level \"$verdict\" of \"approved\" or \"changes_requested\". A Blocker or Major finding sets\n" +
          "      \"changes_requested\" so it is fixed in this same review pass; Minor and Nit do not. Output\n" +
          "      carrying NEITHER signal fails the schema gate loudly — a prose summary alone cannot advance.\n" +
          "      Each finding's \"severity\" MUST be exactly one of blocker, major, minor, nit, and its\n" +
          "      \"issue_category\" MUST be exactly one of " +
          ReviewIssueCategory.entries.joinToString { it.wireValue } + "; any other category value is\n" +
          "      recorded as other."
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT -> auditProducedOutputsAddendum(
        verdict = verdict,
        briefing = briefing,
      )
      else -> ""
    }
  }

  private fun auditProducedOutputsAddendum(verdict: String, briefing: FeatureTaskRuntimePhaseLaunchBriefing): String =
    "\n    - This is a VERIFYING phase. Set top-level \"$verdict\" to \"satisfied\" or \"gaps_found\" and\n" +
      "      emit exactly one shallow produced_outputs.gaps array. Use [] for satisfied. For gaps_found,\n" +
      "      each entry contains only criterion, severity (blocker or major), location, optional file, " +
      "issue, and fix.\n" +
      "      location MUST be ClassName or ClassName.memberName, never a package-qualified class name.\n" +
      "      file is the basename only and SHOULD be omitted unless the location is ambiguous. Example:\n" +
      "      {\"criterion\":\"AC-003\",\"severity\":\"major\",\"location\":\"ReviewRunner.merge\",\n" +
      "       \"file\":\"ReviewRunner.kt\",\"issue\":\"Rejected lanes are omitted\",\n" +
      "       \"fix\":\"Include rejected lanes in the aggregate\"}.\n" +
      "      Do not emit unmet_criteria, audit_repair_plan, gap IDs, repair-item IDs, dependency arrays,\n" +
      "      acceptance-criterion text, or repeated paths; the runtime derives its durable repair model.\n" +
      "      Minor and nit entries go only in produced_outputs.non_blocking_findings and they\n" +
      "      NEVER trigger gaps_found. Those entries use their own shape, not the gap shape:\n" +
      "      severity (minor or nit) is required, acceptance_criterion_ref and message are expected.\n" +
      "      Example: {\"acceptance_criterion_ref\":\"AC-004\",\n" +
      "       \"message\":\"Naming could be clearer\",\"severity\":\"nit\"}.\n" +
      "      TEST EXCLUSION: missing tests, weak tests, incomplete test coverage, unrealistic fixtures,\n" +
      "      insufficient assertions, and any other test-only concern are NEVER audit gaps. Do not inspect\n" +
      "      or assess test adequacy, cite test files as an affected boundary, or create repair items that\n" +
      "      add or change tests. Validation owns test execution and failures. Audit may report only a\n" +
      "      concrete defect in production behavior or production implementation; when no such defect is\n" +
      "      evidenced, emit satisfied even if test coverage is absent or inadequate.\n" +
      auditRoundScopeAddendum(briefing) +
      auditClosedCriterionAddendum(briefing.durablyClosedCriterionRefs)

  private fun auditRoundScopeAddendum(briefing: FeatureTaskRuntimePhaseLaunchBriefing): String =
    if (briefing.unresolvedAuditGapIds.isEmpty()) {
      "      INITIAL AUDIT SCOPE: inspect every listed acceptance criterion once. " +
        "PROSPECTIVE REPAIR IMPACT ANALYSIS\n" +
        "      belongs only to this initial pass: before reporting gaps, analyze the complete proposed repair batch\n" +
        "      so each fix is closure-complete for that blast radius. Treat " +
        "already-satisfied criteria as non-regression constraints\n" +
        "      and account for the cumulative repair delta and cross-repair interactions\n" +
        "      now, allowing every evidenced gap to be repaired together in one implementation invocation. Verify\n" +
        "      concrete production behavior and do not invent speculative gaps.\n"
    } else {
      "      FOLLOW-UP AUDIT SCOPE: disposition every carried unresolved gap and inspect the repair work performed\n" +
        "      for them in this round (${briefing.unresolvedAuditGapIds.joinToString()}). Reverify each original\n" +
        "      failure_evidence check, then inspect the cumulative repair delta and its directly affected " +
        "production\n" +
        "      boundaries for newly introduced gaps before emitting satisfied. Do not rescan unrelated subtask or\n" +
        "      acceptance-criterion surfaces, and never classify test-only concerns as audit gaps. A recurring\n" +
        "      disposition is legal ONLY when the carried gap's ORIGINAL failure_evidence check still fails " +
        "at its\n" +
        "      recorded artifact_ref; a stricter reading of the criterion, a new concern at the same location, or\n" +
        "      a preference for a different repair approach never makes a resolved gap recurring. Emit compact gaps\n" +
        "      for recurring carried identities and any concrete new production gaps in the repair blast radius;\n" +
        "      emit satisfied with gaps [] only when neither category remains.\n"
    }

  private fun auditClosedCriterionAddendum(closedCriterionRefs: List<String>): String =
    if (closedCriterionRefs.isEmpty()) {
      ""
    } else {
      "\n      The acceptance criteria ${closedCriterionRefs.sorted().joinToString()} already reached a " +
        "satisfied verdict and are durably closed: verify ONLY the criteria this briefing still lists, and " +
        "never report a compact gap against a closed criterion. Doing " +
        "so fails the schema gate loudly."
    }

  // The preplan, plan, and implement phases each emit a bounded planning projection that the NEXT
  // phase's launch seam parses with additionalProperties:false against
  // feature-task-runtime-planning-projections-schema.yaml. Naming the fields in prose (as the phase
  // directive does) is not enough to hit the shape: the projection lives DIRECTLY on produced_outputs
  // (never nested under a projection_kind-named key), rollout and each deviations entry are OBJECTS,
  // and task_id is lowercase-kebab. An agent left to infer the shape emits a nested wrapper, a prose
  // rollout string, a free-text deviation, or "T1" and is rejected at the seam. Each example mirrors
  // PlanningProjectionFixtures so the guidance and the gate cannot drift.
  private fun planningProjectionShapeExampleFor(phaseId: String): String = when (phaseId) {
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN -> PREPLAN_PROJECTION_SHAPE
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN -> PLAN_PROJECTION_SHAPE
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT -> IMPLEMENT_PROJECTION_SHAPE
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE -> VALIDATION_PROJECTION_SHAPE
    else -> ""
  }

  private val PREPLAN_PROJECTION_SHAPE: String =
    "\n    - Required produced_outputs shape: emit these fields DIRECTLY on produced_outputs — do NOT\n" +
      "      nest them under a \"preplanning_digest\" key — and \"rollout\" is an OBJECT, never a string:\n" +
      "      ```json\n" +
      "      { \"projection_kind\": \"preplanning_digest\",\n" +
      "        \"contract_version\": \"$FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION\",\n" +
      "        \"affected_boundaries\": [\"<module or boundary touched>\"], \"patterns_and_decisions\": [],\n" +
      "        \"complexity_signals\": { \"task_count\": 1, \"dependency_depth\": 0, \"module_breadth\": 1,\n" +
      "          \"boundary_breadth\": 1, \"persistence_or_migration\": false, \"security_or_privacy\": false,\n" +
      "          \"concurrency_or_lifecycle\": false, \"process_boundary_or_crash_recovery\": false,\n" +
      "          \"platform_count\": 1, \"expected_changed_path_count\": 1 },\n" +
      "        \"risks\": [\"<concrete risk>\"],\n" +
      "        \"rollout\": { \"flag_required\": false, \"flag_pattern\": \"none\",\n" +
      "          \"notes\": \"<rollout note, or N/A>\" },\n" +
      "        \"validation_strategy\": [\"<how the change is validated>\"],\n" +
      "        \"unresolved_questions\": [], \"evidence_refs\": [] }\n" +
      "      ```\n" +
      "      flag_pattern is one of none, simple_conditional, di_switch, legacy. Optional arrays may be\n" +
      "      omitted or []; every listed string must be non-empty."

  private val PLAN_PROJECTION_SHAPE: String =
    "\n    - Required produced_outputs shape: emit these fields DIRECTLY on produced_outputs. Every\n" +
      "      task_id MUST match ^[a-z][a-z0-9-]*\$ (lowercase kebab; \"T1\" is REJECTED — use \"task-1\") and\n" +
      "      criterion_refs use the AC-### form:\n" +
      "      ```json\n" +
      "      { \"projection_kind\": \"executable_plan\",\n" +
      "        \"contract_version\": \"$FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION\",\n" +
      "        \"mode\": \"direct\",\n" +
      "        \"complexity_signals\": { \"task_count\": 1, \"dependency_depth\": 0, \"module_breadth\": 1,\n" +
      "          \"boundary_breadth\": 1, \"persistence_or_migration\": false, \"security_or_privacy\": false,\n" +
      "          \"concurrency_or_lifecycle\": false, \"process_boundary_or_crash_recovery\": false,\n" +
      "          \"platform_count\": 1, \"expected_changed_path_count\": 1 },\n" +
      "        \"tasks\": [ { \"task_id\": \"task-1\", \"depends_on\": [], \"description\": \"<imperative task>\",\n" +
      "          \"criterion_refs\": [\"AC-001\"], \"target_paths_or_symbols\": [\"path/or/Symbol\"],\n" +
      "          \"test_obligations\": [\"<test to add or run>\"], \"constraints\": [] } ],\n" +
      "        \"validation_strategy\": [\"<how the plan is validated>\"] }\n" +
      "      ```"

  private val IMPLEMENT_PROJECTION_SHAPE: String =
    "\n    - Required produced_outputs shape: emit the implementation_receipt fields DIRECTLY on\n" +
      "      produced_outputs (the bounded claim audit consumes) alongside the reconciled_state report.\n" +
      "      completed_task_ids reuse the plan's task_ids; changed_paths are repository-relative; every\n" +
      "      deviations entry is an OBJECT { \"ref\", \"note\" }, never a free-text string:\n" +
      "      ```json\n" +
      "      { \"projection_kind\": \"implementation_receipt\",\n" +
      "        \"contract_version\": \"$FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION\",\n" +
      "        \"completed_task_ids\": [\"task-1\"], \"changed_paths\": [\"path/Changed.kt\"],\n" +
      "        \"tests_added\": [], \"tests_updated\": [],\n" +
      "        \"tests_executed\": [],\n" +
      "        \"deviations\": [ { \"ref\": \"task-1\", \"note\": \"<one-line what deviated and why>\" } ],\n" +
      "        \"unresolved_items\": [],\n" +
      "        \"reconciliation_evidence\": { \"reconciled\": true, \"evidence\": \"<tree at target>\" },\n" +
      "        \"repository_checkpoint\": { \"fingerprint\": \"<checkpoint fingerprint>\" },\n" +
      "        \"reconciled_state\": { \"reconciled\": true, \"evidence\": \"<tree at target>\" } }\n" +
      "      ```\n" +
      "      Compilation and test execution belong exclusively to the validate phase. Do NOT build,\n" +
      "      compile, or run tests here: write the tests the plan obligates and leave them unexecuted.\n" +
      "      tests_executed stays [] in this phase; validate runs them and owns their outcomes.\n" +
      "      deviations may be []; each note is a single line without backticks or pasted JSON/diff\n" +
      "      payloads."

  private const val VALIDATION_PROJECTION_SHAPE: String =
    "\n    - Required produced_outputs shape: emit a validation_result OBJECT. Its repository_checkpoint\n" +
      "      is also an OBJECT containing fingerprint — never a prefixed string such as\n" +
      "      \"repository_checkpoint=<hash>\":\n" +
      "      ```json\n" +
      "      { \"validation_result\": {\n" +
      "          \"validation_status\": \"passed\",\n" +
      "          \"checks\": [ { \"name\": \"<check name>\", \"status\": \"passed\" } ],\n" +
      "          \"repository_checkpoint\": { \"fingerprint\": \"<checkpoint fingerprint>\" }\n" +
      "        } }\n" +
      "      ```"

  // repair_item_results and reconciled_state are co-residents on the implementation_receipt the audit
  // consumer parses, not a replacement for it. Presenting them under their own "Required
  // produced_outputs shape" heading made the phase emit only those two keys and fail the receipt gate
  // on every attempt until the loop cap.
  private fun auditRemediationOutputExample(repairItemIds: List<String>): String =
    "\n      Required produced_outputs shape: the SAME implementation_receipt object, carrying the two\n" +
      "      remediation fields alongside its own. They are co-residents, NOT a replacement shape:\n" +
      "      projection_kind, contract_version, and every other receipt field stay REQUIRED here:\n" +
      "      ```json\n" +
      "      { \"projection_kind\": \"implementation_receipt\",\n" +
      "        \"contract_version\": \"$FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION\",\n" +
      "        \"completed_task_ids\": [\"task-1\"], \"changed_paths\": [\"path/Changed.kt\"],\n" +
      "        \"tests_added\": [], \"tests_updated\": [], \"tests_executed\": [],\n" +
      "        \"deviations\": [], \"unresolved_items\": [],\n" +
      "        \"reconciliation_evidence\": { \"reconciled\": true, \"evidence\": \"<tree at target>\" },\n" +
      "        \"repository_checkpoint\": { \"fingerprint\": \"<checkpoint fingerprint>\" },\n" +
      "        \"reconciled_state\": { \"reconciled\": true, \"evidence\": \"<verified end state>\" },\n" +
      "        \"deferred_repair_item_ids\": [],\n" +
      "        \"repair_item_results\": ${repairItemResultsJson(repairItemIds)} }\n" +
      "      ```"

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
}

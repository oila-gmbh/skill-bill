package skillbill.application.featuretask

import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.application.model.FeatureTaskRuntimeImplementationContinuation
import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.review.model.ReviewIssueCategory
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_AUDIT_NOTE_MAX_CHARS
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorReviewContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedger
import skillbill.workflow.taskruntime.model.GoalSubtaskCommitFocusedAccounting

/**
 * Pure composer of the full prompt a feature-task-runtime phase agent receives. The persisted
 * per-phase briefing is the durable handoff record; this prompt is the delivered copy of it,
 * framed with the phase task directive and the phase-output contract the schema gate enforces.
 * Without this delivery the agent would receive the default goal-continuation prompt and could
 * never produce schema-valid phase output.
 */
@Suppress("TooManyFunctions") // one cohesive prompt-composition seam; each function is a named directive
object FeatureTaskRuntimePhasePromptComposer {
  /** Indent the phase directives are authored against; stripped when the header is composed. */
  private const val TEMPLATE_INDENT = "      "

  @Suppress(
    "LongParameterList",
    "LongMethod",
  ) // one cohesive phase-prompt delivery; bundling these would only hide them
  fun compose(
    issueKey: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    suppressDecomposition: Boolean = false,
    parallelReviewAgent: String? = null,
    codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.DEFAULT,
    reviewPassNumber: Int? = null,
    goalSubtaskReviewInput: GoalSubtaskReviewInput? = null,
    baselineUntrackedPaths: List<String> = emptyList(),
    resolvedReviewTier: CodeReviewExecutionMode? = null,
    reviewDecidingRule: String? = null,
    priorSchemaFailure: String? = null,
    priorTerminalFailure: String? = null,
    priorFindingCoverage: String? = null,
    correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext? = null,
    operatorBlockRetry: FeatureTaskRuntimeOperatorBlockRetry? = null,
    implementationContinuation: FeatureTaskRuntimeImplementationContinuation? = null,
    validationGateFindings: ValidationFindingSetProjection? = null,
    agentRunValidateFallback: Boolean = false,
    packCollectAllCommand: String? = null,
    packBuildCommand: String? = null,
    repairLedger: FeatureTaskRuntimeRepairLedger? = null,
    priorReviewContext: FeatureTaskRuntimePriorReviewContext? = null,
  ): String {
    requireComposableInputs(
      issueKey = issueKey,
      priorSchemaFailure = priorSchemaFailure,
      priorTerminalFailure = priorTerminalFailure,
      priorFindingCoverage = priorFindingCoverage,
      correctiveRepairContext = correctiveRepairContext,
    )
    // Schema-correction retries suppress any durable continuation projection instead of rejecting the
    // combination: after incomplete mutating work, the next launch may still carry both, and the
    // corrective path must render only the schema rejection plus authorized repair context.
    val effectiveContinuation = implementationContinuation.takeUnless { correctiveRepairContext != null }
    return listOf(
      header(
        issueKey,
        briefing.phaseId,
        agentRunValidateFallback,
        packCollectAllCommand,
        packBuildCommand,
        briefing.priorGapMemory,
        validationGateFindings != null,
      ),
      ceremonyDirective(briefing),
      mutatingPhaseIdempotencyDirective(briefing.phaseId),
      nonValidatePhaseValidationOwnershipDirective(briefing.phaseId),
      nonBuildPhaseBuildOwnershipDirective(briefing.phaseId),
      minimalismDisciplineDirective(briefing.phaseId),
      testValueDisciplineDirective(briefing.phaseId),
      priorGapMemoryRemediationDirective(briefing.phaseId, briefing.priorGapMemory),
      goalContinuationDirective(briefing.phaseId, suppressDecomposition),
      absentValidationGateDegradationDirective(briefing.phaseId, agentRunValidateFallback),
      validationGateFindingsDirective(briefing.phaseId, validationGateFindings),
      reviewExecutionDirective(
        briefing.phaseId,
        ReviewExecutionDirectiveInputs(
          codeReviewMode = codeReviewMode,
          parallelReviewAgent = parallelReviewAgent,
          goalSubtaskReviewInput = goalSubtaskReviewInput,
          reviewPassNumber = reviewPassNumber,
          resolvedReviewTier = resolvedReviewTier,
          reviewDecidingRule = reviewDecidingRule,
          baselineUntrackedPaths = baselineUntrackedPaths,
          repairLedger = repairLedger,
          priorReviewContext = priorReviewContext,
        ),
      ),
      commitExclusionDirective(briefing.phaseId, issueKey),
      briefing.briefingText,
      operatorBlockRetryDirective(briefing.phaseId, operatorBlockRetry),
      // At most one correction path is rendered: schema-correction suppresses continuation above;
      // retryable-terminal stays exclusive via the require; incomplete-work alone keeps continuation.
      implementationContinuationDirective(briefing.phaseId, effectiveContinuation),
      retryCorrectionDirective(briefing, priorSchemaFailure, correctiveRepairContext),
      terminalRetryDirective(priorTerminalFailure),
      findingCoverageDirective(priorFindingCoverage),
      if (validationGateFindings != null) {
        gateRepairNoOutputSchemaDirective(briefing.phaseId)
      } else {
        outputContract(briefing, agentRunValidateFallback)
      },
    ).filter(String::isNotBlank).joinToString(separator = "\n\n")
  }

  /** The mutually-exclusive correction combinations a composed prompt may never carry at once. */
  private fun requireComposableInputs(
    issueKey: String,
    priorSchemaFailure: String?,
    priorTerminalFailure: String?,
    priorFindingCoverage: String?,
    correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext?,
  ) {
    require(issueKey.isNotBlank()) { "issueKey is required to compose a phase prompt." }
    require(correctiveRepairContext == null || !priorSchemaFailure.isNullOrBlank()) {
      "correctiveRepairContext requires a non-blank priorSchemaFailure; raw repair context belongs " +
        "only to schema-gate retries."
    }
    require(correctiveRepairContext == null || priorTerminalFailure.isNullOrBlank()) {
      "correctiveRepairContext cannot accompany a retryable-terminal failure; the correction kinds " +
        "must stay separate."
    }
    require(priorFindingCoverage.isNullOrBlank() || priorSchemaFailure.isNullOrBlank()) {
      "priorFindingCoverage cannot accompany a schema-gate failure; a receipt is either short of its " +
        "carried findings or rejected, never both in one correction."
    }
  }

  /**
   * Gate-repair launches fix code from runtime-parsed findings in prose. The phase receipt is minted
   * by the coordinator after it re-runs the pack command — no schema envelope, no structured plan
   * object, and no subagents that need structured input.
   */
  private fun gateRepairNoOutputSchemaDirective(phaseId: String): String = """
    ## Gate repair — prose only, no phase-output schema
    This launch is a repair turn for the runtime-owned `$phaseId` gate. Do not emit a Required final
    output JSON object, build_receipt, validation_receipt, gate_run_count, or any other phase envelope.
    Do not spawn delegated subagents. Work in this single agent session in ordinary prose.

    The runtime already ran the pack command and parsed the failures listed in this briefing. It will
    re-run that command after you stop, and it may give you up to three repair turns against whatever
    remains. Address every open finding in this turn — all at once, not one finding per turn.

    Before editing, do brief reasoned planning in prose for each finding (or for a shared root cause
    that covers several). Scale the plan to the finding:
    - Small / obvious: a few lines of due diligence, then fix.
    - Complex: a real short plan — blast radius, surrounding callers/contracts you checked, whether
      the change can introduce new bugs, and how you will keep the fix local.

    No defined plan schema. Do the thinking, then edit. When you are done fixing, stop.
    Never silence findings with @Suppress, @file:Suppress, baselines, disabled rules, weakened
    configuration, or skipped tests — fix the root cause instead.
  """.trimIndent()

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
  //
  // When [correctiveRepairContext] is present, the authorized repair projection is rendered as its own
  // section after the payload-free rejection reason and before remediation/skeleton guidance. The
  // required final-output contract stays later in the prompt, outside that untrusted section.
  private fun retryCorrectionDirective(
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    priorSchemaFailure: String?,
    correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext?,
  ): String {
    if (priorSchemaFailure.isNullOrBlank()) {
      return ""
    }
    val base = """
      ## Previous attempt was REJECTED by the schema gate — salvage the capture
      Programmatic extraction and shape repair could not accept the previous output. Reason:
      $priorSchemaFailure
      This is the last salvage attempt. Rewrite the untrusted prior output into exactly one JSON object
      matching the expected shape below. Keep facts already in that capture; do not redo the phase work
      and do not emit a second envelope. The runtime will extract and validate the result the same way;
      if it still fails, the run blocks.

      Expected shape:
    """.trimIndent() + "\n" + retrySkeleton(briefing)
    val structuralRepairNote = correctiveRepairContext?.structuralRepairEvidence?.let { evidence ->
      "\nDeterministic syntax repair previously succeeded on this capture (delimiter-only; " +
        "original_digest=${evidence.originalDigest} repaired_digest=${evidence.repairedDigest} " +
        "source=${evidence.sourceLocation.sourceLabel}:" +
        "${evidence.sourceLocation.line}:${evidence.sourceLocation.column}). " +
        "That does not mean the phase schema accepted it; correct the named schema or semantic violation."
    } ?: if (correctiveRepairContext?.acceptedAfterStructuralRepair == true) {
      "\nDeterministic syntax repair previously succeeded on this capture (delimiter-only). " +
        "That does not mean the phase schema accepted it; correct the named schema or semantic violation."
    } else {
      ""
    }
    val repairProjection = correctiveRepairContext?.let { context ->
      "\n\n" + context.promptProjection().renderAuthorizedRepairSection()
    }.orEmpty()
    return base + structuralRepairNote + repairProjection +
      unparseableRootCorrection(priorSchemaFailure) +
      FeatureTaskRuntimeSchemaFailureCorrections.lengthViolation(priorSchemaFailure) +
      FeatureTaskRuntimeSchemaFailureCorrections.closedEnumeration(priorSchemaFailure) +
      FeatureTaskRuntimeSchemaFailureCorrections.unreconciledReceipt(priorSchemaFailure)
  }

  private fun unparseableRootCorrection(priorSchemaFailure: String): String {
    val rootNotParseable = priorSchemaFailure.contains("<root> must be an object") ||
      priorSchemaFailure.contains("Phase output is malformed")
    if (!rootNotParseable) {
      return ""
    }
    return "\nThe runtime could NOT parse a single JSON object out of your previous output — you likely " +
      "answered\nwith prose, a Markdown table, or a JSON array. None of those can advance the gate. Salvage " +
      "that capture into the expected shape above."
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
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS -> "  \"$verdict\": \"findings_verified\","
      else -> null
    }
  }

  private fun producedOutputsSkeletonEntry(briefing: FeatureTaskRuntimePhaseLaunchBriefing): String =
    when (briefing.phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT -> auditProducedOutputsSkeleton(briefing)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
        "\"${FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS}\": [], " +
          "\"${FeatureTaskRuntimeVerificationSignalKeys.REVIEW_RUN_ID}\": \"<the Review run ID this pass " +
          "reported>\""
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS ->
        "\"${FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS}\": [ " +
          "{ \"finding_id\": \"F-001\", \"disposition\": \"verified\", " +
          "\"reason\": \"<bounded reason against spec intent>\", \"severity\": \"major\", " +
          "\"location\": \"<location>\", \"message\": \"<finding message>\" } ]"
      else -> "\"result\": \"<concrete output for downstream phases>\""
    }

  private fun auditProducedOutputsSkeleton(briefing: FeatureTaskRuntimePhaseLaunchBriefing): String {
    val gaps = "\"${FeatureTaskRuntimeVerificationSignalKeys.AUDIT_GAPS}\": []"
    val nonBlocking =
      "\"${FeatureTaskRuntimeVerificationSignalKeys.AUDIT_NON_BLOCKING_FINDINGS}\": []"
    if (briefing.unresolvedAuditGapIds.isEmpty()) return "$gaps, $nonBlocking"
    val dispositions = briefing.unresolvedAuditGapIds.joinToString(",") { id ->
      "{\"gap_id\":\"$id\",\"status\":\"resolved\",\"evidence\":{" +
        "\"observation\":\"resolution_verified\",\"artifact_ref\":\"Type.kt:Type.member\"," +
        "\"check_ref\":\"AC-001\"}}"
    }
    return "$gaps, $nonBlocking, \"carried_gap_dispositions\": [$dispositions], " +
      "\"blast_radius_inspection\": {\"inspected_paths\":[\"Type.kt\"],\"newly_introduced_gap_ids\":[]," +
      "\"evidence\":{\"observation\":\"resolution_verified\",\"artifact_ref\":\"Type.kt\"," +
      "\"check_ref\":\"AC-001\"}}"
  }

  // The forward phase order as prose, derived from the single topology source so the briefing can
  // never drift from the graph the runtime actually drives. Loop-only phases are excluded because a
  // clean run never launches them.
  private val forwardPhaseOrder: String =
    FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds
      .filterNot { it in FeatureTaskRuntimePhaseWorkflowDefinition.transitions.loopOnlyPhaseIds }
      .joinToString(" -> ")

  @Suppress("LongParameterList")
  private fun header(
    issueKey: String,
    phaseId: String,
    agentRunValidateFallback: Boolean = false,
    packCollectAllCommand: String? = null,
    packBuildCommand: String? = null,
    priorGapMemory: FeatureTaskRuntimePriorGapMemory? = null,
    validationGateRepair: Boolean = false,
  ): String {
    val label = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepLabels[phaseId] ?: phaseId
    val directive = phaseTaskDirective(
      phaseId,
      PhaseTaskDirectiveArgs(
        agentRunValidateFallback = agentRunValidateFallback,
        packCollectAllCommand = packCollectAllCommand,
        packBuildCommand = packBuildCommand,
        priorGapMemory = priorGapMemory,
        validationGateRepair = validationGateRepair,
      ),
    )
    // Composed directly rather than as an indented raw string with trimIndent(). trimIndent() runs
    // after interpolation and strips only the common minimal indent, so one column-0 line anywhere
    // in $directive drives that common indent to zero and dedents nothing — the header then reaches
    // the agent indented, hiding the `Phase:` line from line-anchored readers and rendering as a
    // markdown code block. Directive continuation lines are authored with TEMPLATE_INDENT so the
    // old raw string aligned; that prefix is stripped explicitly here, which reproduces the intended
    // output for every directive instead of depending on all of them staying well-formed.
    return buildString {
      appendLine("You are executing exactly one phase of the EXPERIMENTAL skill-bill feature-task-runtime")
      appendLine("loop ($forwardPhaseOrder)")
      appendLine("for issue $issueKey. The runtime owns the loop; do not run other phases, do not open")
      appendLine("or continue any other skill-bill workflow, and do not call `skill-bill workflow continue`.")
      appendLine()
      appendLine("Phase: $phaseId ($label)")
      append("Task: ")
      append(directive.lineSequence().joinToString("\n") { it.removePrefix(TEMPLATE_INDENT) })
    }
  }

  private fun ceremonyDirective(briefing: FeatureTaskRuntimePhaseLaunchBriefing): String {
    val featureSize = FeatureTaskRuntimeFeatureSize.fromWire(briefing.featureSize)
    val scaling = FeatureTaskRuntimePhaseWorkflowDefinition.ceremonyScaling(featureSize)
    val reviewScope = scaling.reviewScope.wireValue
    val phaseSpecific = when (briefing.phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN ->
        "Apply ${scaling.preplanCeremony.promptLabel}. Keep the gate real: identify concrete scope, " +
          "affected boundaries, risks, and unknowns at the requested depth."
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
        "The runtime owns ${scaling.reviewScope.promptLabel}. Keep the review gate real: inspect the implemented " +
          "change for defects and record concrete file references."
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT ->
        "Apply ${scaling.auditCeremony.promptLabel}. Keep the audit gate real: verify acceptance " +
          "criteria, report concrete gaps, and attach a complete blast-radius-aware fix plan in each " +
          "gap note so implement can close the gap without opening a new one."
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

  private fun outputContract(
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    agentRunValidateFallback: Boolean,
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
      agentRunValidateFallback,
    )}
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
  private fun producedOutputsAddendum(
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    agentRunValidateFallback: Boolean,
  ): String {
    val phaseId = briefing.phaseId
    if (FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(phaseId)) {
      return mutatingProducedOutputsAddendum(briefing, agentRunValidateFallback)
    }
    val findings = FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS
    val verdict = FeatureTaskRuntimeVerificationSignalKeys.VERDICT
    return when (phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
      -> FeatureTaskRuntimePhaseProjectionShapes.exampleFor(
        phaseId,
        agentRunValidateFallback,
      )
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
        "\n    - This is a VERIFYING phase: produced_outputs MUST carry a \"$findings\" array (each entry a\n" +
          "      severity/message object; an explicit empty [] affirms no Blocker or Major findings) AND/OR a\n" +
          "      top-level \"$verdict\" of \"approved\" or \"changes_requested\". A Blocker or Major finding sets\n" +
          "      \"changes_requested\" so it is fixed in this same review pass; Minor and Nit do not. Output\n" +
          "      carrying NEITHER signal fails the schema gate loudly — a prose summary alone cannot advance.\n" +
          "      Each finding's \"severity\" MUST be exactly one of blocker, major, minor, nit, and its\n" +
          "      \"issue_category\" MUST be exactly one of " +
          ReviewIssueCategory.entries.joinToString { it.wireValue } + "; any other category value is\n" +
          "      recorded as other.\n" +
          "    - produced_outputs MUST also carry \"${FeatureTaskRuntimeVerificationSignalKeys.REVIEW_RUN_ID}\": the " +
          "Review run ID your\n" +
          "      `bill-code-review` invocation reported for this pass, verbatim. It is the key that joins each\n" +
          "      finding here to the imported review run, so a finding's \"id\" plus this run id must be the same\n" +
          "      pair that review recorded. Omit it ONLY if the review genuinely reported no run id; never\n" +
          "      invent, reuse an older, or guess one." + commitFocusedAccountingAddendum()
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS ->
        "\n    - This is a VERIFYING phase: set top-level \"$verdict\" to \"findings_verified\" or " +
          "\"no_findings_verified\" and emit exactly one " +
          "produced_outputs.${FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS} " +
          "array with one entry per review finding. Recommended fields per entry: finding_id, disposition " +
          "(verified or rejected), reason, severity, location, message, optional " +
          "selected_boundary_headings (heading_id and source_path), and boundary_context_unavailable " +
          "when no eligible boundary owns the finding paths. Boundary memory is optional supporting " +
          "evidence only: the disposition still settles from spec intent when headings are omitted or " +
          "invalid. When you cite boundary memory, copy heading_id and source_path verbatim from that " +
          "finding's boundary_catalog only — never invent hashes or reuse another finding's catalog. " +
          "The runtime reads dispositions leniently and does not block on reason length, extra keys, " +
          "invalid boundary selections, or other schema polish; decidable verification signals still " +
          "gate advancement. Do not edit the worktree.\n" +
          "      Example: {\"finding_id\":\"F-001\",\"disposition\":\"verified\"," +
          "\"reason\":\"Matches spec intent AC-002.\",\"severity\":\"major\"," +
          "\"location\":\"FeatureTaskRuntimePhaseWorkflowDefinition.kt\"," +
          "\"message\":\"Missing verify_findings wiring\"," +
          "\"selected_boundary_headings\":[{\"heading_id\":\"runtime-kotlin/agent/history.md#abc\"," +
          "\"source_path\":\"runtime-kotlin/agent/history.md\"}]}."
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT -> auditProducedOutputsAddendum(
        verdict = verdict,
        briefing = briefing,
      )
      else -> ""
    }
  }

  private fun mutatingProducedOutputsAddendum(
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    agentRunValidateFallback: Boolean,
  ): String {
    val phaseId = briefing.phaseId
    val remediation = if (briefing.unresolvedAuditGapIds.isEmpty()) {
      ""
    } else {
      "\n    - This is AUDIT-GAP REMEDIATION for these acceptance criteria: " +
        briefing.unresolvedAuditGapIds.joinToString() + ". Each listed gap already carries its " +
        "implement-ready fix plan in the note after the criterion ref. Follow that plan completely " +
        "in this one invocation: execute the planned production change, respect its blast radius, " +
        "and check surrounding callers/contracts so the repair does not open a new gap or regress " +
        "a neighboring criterion. Do not invent a narrower substitute plan. Then report the ordinary " +
        "implementation receipt. There are no repair-item identifiers to echo and no per-item " +
        "evidence to record: the next audit re-reads the tree and decides every criterion again. If " +
        "a criterion is genuinely unimplementable, leave through a blocked envelope naming it and why."
    }
    return "\n    - produced_outputs MUST include a reconciliation report: a \"reconciled_state\" object\n" +
      "      (or a \"reconciled_state\" entry) with \"reconciled\": true and concrete evidence that the\n" +
      "      changed files are at their intended target state. A status of \"completed\" with the\n" +
      "      reconciliation report missing or \"reconciled\" not true fails the schema gate loudly." +
      FeatureTaskRuntimePhaseProjectionShapes.exampleFor(
        phaseId,
        agentRunValidateFallback,
      ) + remediation
  }

  /**
   * The only seam that instructs a review pass to emit `produced_outputs.commit_focused_accounting`.
   * The lifecycle reducer reads that key and durable review state requires the record for a delegated
   * pass over a real commit sequence; without this instruction the key is never written and every
   * delegated pass persists a null accounting record. Inline and non-commit passes omit the key rather
   * than fabricating a sequence identity, which is why the instruction is conditional on what the pass
   * actually ran instead of unconditional.
   */
  private fun commitFocusedAccountingAddendum(): String =
    "\n    - If this pass ran a DELEGATED review over a real commit sequence, produced_outputs MUST also\n" +
      "      carry \"commit_focused_accounting\" exactly as the review reported it: commit_sequence_digest\n" +
      "      (64-char lowercase hex), commit_count, lane_count, focused_commit_count,\n" +
      "      skipped_commit_count (focused + skipped == commit_count), and integration_terminal_outcome,\n" +
      "      one of " + GoalSubtaskCommitFocusedAccounting.INTEGRATION_TERMINAL_OUTCOMES.sorted()
        .joinToString() + ".\n" +
      "      Optional when the review reported them: routing_digest, focused_pair_count,\n" +
      "      skipped_pair_count, lane_bundle_sizes, lane_segment_counts, incomplete_lanes,\n" +
      "      parent_analysis_pairs, parent_analysis_bytes, integration_finding_count, and\n" +
      "      integration_skip_reason (REQUIRED when integration_terminal_outcome is\n" +
      "      ${GoalSubtaskCommitFocusedAccounting.SKIPPED_NOT_APPLICABLE}). Lanes that ended incomplete\n" +
      "      are named in incomplete_lanes; that is non-clean coverage and the integration pass never\n" +
      "      compensates for it. Identities, counts, and lane names ONLY — never a commit subject, a\n" +
      "      path, or diff text. An INLINE or non-commit-sequence pass OMITS the key entirely rather\n" +
      "      than fabricating a sequence identity; never invent or guess a digest or a count."

  private fun auditProducedOutputsAddendum(verdict: String, briefing: FeatureTaskRuntimePhaseLaunchBriefing): String =
    "\n    - This is a VERIFYING phase. Ignore the optional-verdict bullet above: for audit, top-level " +
      "\"$verdict\" is REQUIRED. Copy exactly one token: satisfied | gaps_found.\n" +
      "      REJECTED: omitting verdict; any other string; nesting verdict only inside produced_outputs.\n" +
      "      ACCEPTED root: {\"contract_version\":\"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION\"," +
      "\"phase_id\":\"audit\",\"status\":\"completed\",\"verdict\":\"satisfied\"," +
      "\"summary\":\"<one sentence>\",\"produced_outputs\":{\"gaps\":[]}}.\n" +
      "      Emit exactly one shallow produced_outputs.gaps array. Use [] for satisfied. For\n" +
      "      gaps_found, one entry per unmet criterion: {\"criterion\":\"AC-003\",\"note\":\"...\"}.\n" +
      "      Recommended wire shape uses those two fields — free-form note prose; no required keywords\n" +
      "      or sections. The runtime does not block on note length, extra gap keys, or other schema\n" +
      "      polish; it reads gaps leniently and gates only on decidable verification signals.\n" +
      "      criterion is AC-###. note is one dense line of at most " +
      "$FEATURE_TASK_RUNTIME_AUDIT_NOTE_MAX_CHARS characters. Prefer notes that both name what is\n" +
      "      missing and give implement enough of a fix plan to close the gap carefully: the intended\n" +
      "      production change, blast radius on callers/DI/sibling phases/contracts, and how to avoid\n" +
      "      regressing neighbors or opening a new gap. Prefer a complete correct plan over a narrow\n" +
      "      patch. Never a diff hunk, a source body, or a line number.\n" +
      "      produced_outputs carries nothing else about the audit: no unmet_criteria, no\n" +
      "      audit_repair_plan, no carried_gap_dispositions, no blast_radius_inspection, no gap or\n" +
      "      repair-item identifiers — put planning guidance in the note only.\n" +
      auditNoEarlierAuditLine(briefing) +
      "      Minor and nit entries go only in produced_outputs.non_blocking_findings and they\n" +
      "      NEVER trigger gaps_found: severity (minor or nit) is required, acceptance_criterion_ref and\n" +
      "      message are expected. Example: {\"acceptance_criterion_ref\":\"AC-004\",\n" +
      "       \"message\":\"Naming could be clearer\",\"severity\":\"nit\"}.\n" +
      "      TEST EXCLUSION: missing tests, weak tests, incomplete test coverage, unrealistic fixtures,\n" +
      "      insufficient assertions, and any other test-only concern are NEVER unmet criteria. Do not\n" +
      "      inspect or assess test adequacy and do not cite test files. Validation owns test execution\n" +
      "      and failures. Report only a concrete defect in production behavior or production\n" +
      "      implementation; when no such defect is evidenced, emit satisfied even if test coverage is\n" +
      "      absent or inadequate." +
      auditRoundScopeAddendum(briefing)

  // The blank-slate line in the audit output addendum. Subordinated for a memory-carrying audit (which
  // must account for the earlier audit's claims) while staying byte-identical for a first or forward audit.
  private fun auditNoEarlierAuditLine(briefing: FeatureTaskRuntimePhaseLaunchBriefing): String =
    if (briefing.priorGapMemory == null) {
      "      Every audit re-checks every listed criterion from scratch against the tree, so there is no\n" +
        "      earlier audit to account for and nothing to carry forward except the notes you emit now.\n"
    } else {
      "      Every audit re-checks every listed criterion from scratch against the tree; when this\n" +
        "      briefing carries prior-gap memory, the earlier audit's claims are context you must\n" +
        "      account for, and a repeated sticky criterion id needs an explicit re-justification (below).\n"
    }

  // The unmet criteria a previous audit named are the round's focus, but never its boundary: a
  // criterion an earlier audit passed can regress under a later repair, and only a full re-check
  // catches that. Naming the carried refs orients the round without narrowing what it must decide.
  // When prior-gap memory is present, the round also renders the memory (prior refs+notes, the last
  // implement claims, sticky ids) and requires explicit re-justification for any repeated sticky id.
  private fun auditRoundScopeAddendum(briefing: FeatureTaskRuntimePhaseLaunchBriefing): String {
    val memoryBlock = briefing.priorGapMemory?.let { memory ->
      val claims = memory.lastImplementClaims.takeIf { it.isNotEmpty() }?.joinToString() ?: "none"
      val sticky = memory.stickyIds.takeIf { it.isNotEmpty() }?.joinToString() ?: "none"
      buildString {
        append("\n      Prior-gap memory (round ${memory.round}): the audit that fired this edge reported:\n")
        memory.priorUnmetCriteria.forEach { note -> append("        - $note\n") }
        append("      The subsequent implement receipt claimed: $claims.\n")
        append("      Sticky ids (unmet in the last two audits): $sticky.\n")
        append("      Repeating any sticky criterion id requires explicit re-justification: name what\n")
        append("      the prior implement claimed and why the tree still fails it. The blanket 'no earlier\n")
        append("      audit' note above does not apply to a memory-carrying audit.\n")
      }
    }.orEmpty()
    val scopeBlock = if (briefing.unresolvedAuditGapIds.isEmpty()) {
      ""
    } else {
      "\n      The previous audit reported these criteria unmet: " +
        "${briefing.unresolvedAuditGapIds.joinToString()}. Start there, then still decide every listed\n" +
        "      criterion from the tree: a repair can regress a criterion an earlier audit passed, and a\n" +
        "      narrow patch can open a new sibling gap. When you emit gaps_found again, prefer notes\n" +
        "      that still hand implement a careful fix plan, not only a fresh diagnosis."
    }
    return memoryBlock + scopeBlock
  }

  private fun validationGateFindingsDirective(phaseId: String, findings: ValidationFindingSetProjection?): String {
    if (findings == null) return ""
    val (sectionTitle, preamble) = when (phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE -> Pair(
        "## Runtime validation gate findings",
        "A prior gate run parsed these items. They are the full open set for this repair turn — fix " +
          "every one in this session (shared root causes may collapse several into one change). Do not " +
          "run `skill-bill validate`, `bill-code-check`, `./gradlew check`, `check " + "--" + "continue`, " +
          "or the pack collect_all_full_gate_command. Targeted `test`, `compileKotlin`, `detekt`, and " +
          "`ktlintCheck` are allowed while repairing when they are part of the routed pack checker. Do " +
          "not spawn delegated subagents.",
      )
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD -> Pair(
        "## Runtime build gate findings",
        "A prior gate run parsed these items. They are the full open set for this repair turn — fix " +
          "every one in this session (shared root causes may collapse several into one change). Run only " +
          "the pack-declared build command when you need console detail. Do not run `skill-bill " +
          "validate`, `bill-code-check`, `./gradlew check`, `check " + "--" + "continue`, or the pack " +
          "collect_all_full_gate_command. Do not spawn delegated subagents.",
      )
      else -> return ""
    }
    val lines = buildList {
      add(sectionTitle)
      add(preamble)
      findings.findings.forEachIndexed { index, finding ->
        add(
          "${index + 1}. module=${finding.module} id=${finding.ruleOrTestId} " +
            "location=${finding.location ?: "<unknown>"} message=${finding.message}",
        )
      }
    }
    return lines.joinToString("\n")
  }
}

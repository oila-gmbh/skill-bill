package skillbill.application.featuretask

import skillbill.agent.model.AgentPhaseInput
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.application.model.FeatureTaskRuntimeImplementationContinuation
import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorReviewContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedger

/**
 * Pure composer of the full prompt a feature-task-runtime phase agent receives. The persisted
 * per-phase briefing is the durable handoff record; this prompt is the delivered copy of it,
 * framed with phase directives and the AgentPhaseInput requestedAction. Without this delivery
 * the agent would receive the default goal-continuation prompt and could not settle phases.
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
    codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.DEFAULT,
    reviewPassNumber: Int? = null,
    goalSubtaskReviewInput: GoalSubtaskReviewInput? = null,
    baselineUntrackedPaths: List<String> = emptyList(),
    resolvedReviewTier: CodeReviewExecutionMode? = null,
    reviewDecidingRule: String? = null,
    priorSchemaFailure: String? = null,
    priorTerminalFailure: String? = null,
    priorFindingCoverage: String? = null,
    priorDerivationReask: String? = null,
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
      operatorBlockRetryDirective(briefing.phaseId, operatorBlockRetry),
      // At most one correction path is rendered: schema-correction suppresses continuation above;
      // retryable-terminal stays exclusive via the require; incomplete-work alone keeps continuation.
      implementationContinuationDirective(briefing.phaseId, effectiveContinuation),
      retryCorrectionDirective(briefing, priorSchemaFailure, correctiveRepairContext),
      terminalRetryDirective(priorTerminalFailure),
      findingCoverageDirective(priorFindingCoverage),
      derivationReaskDirective(priorDerivationReask),
    ).filter(String::isNotBlank).joinToString(separator = "\n\n")
  }

  fun composeAgentPhaseInput(
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    carriedFindingIds: Set<String> = emptySet(),
    agentRunValidateFallback: Boolean = false,
    packCollectAllCommand: String? = null,
    packBuildCommand: String? = null,
  ): AgentPhaseInput {
    val input = briefing.briefingText.trim()
    require(input.isNotBlank()) { "Phase briefing text must be non-blank to compose AgentPhaseInput." }
    val requestedAction = phaseRequestedAction(
      phaseId = briefing.phaseId,
      carriedFindingIds = carriedFindingIds,
      agentRunValidateFallback = agentRunValidateFallback,
      packCollectAllCommand = packCollectAllCommand,
      packBuildCommand = packBuildCommand,
      priorGapMemory = briefing.priorGapMemory,
    )
    require(requestedAction.isNotBlank()) { "Phase requestedAction must be non-blank." }
    return AgentPhaseInput(input = input, requestedAction = requestedAction)
  }

  fun frameAgentPhaseLaunchPrompt(phaseInput: AgentPhaseInput, directiveSections: String): String =
    buildString {
      appendLine("Phase input:")
      appendLine(phaseInput.input)
      appendLine("Requested action: ${phaseInput.requestedAction}")
      appendLine("Return free-form prose as your phase output. The output string is authoritative.")
      if (directiveSections.isNotBlank()) {
        appendLine()
        append(directiveSections)
      }
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
    output JSON object, build_receipt, validation_receipt, or any other phase envelope.
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
      because of the prior interpretation. The governed acceptance criteria still apply.
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
      ## Previous attempt could not settle — restate in prose
      The runtime could not derive a decisive outcome from the previous output. Reason:
      $priorSchemaFailure
      Restate your phase outcome in plain prose inside your returned text. State status (completed, blocked,
      or failed), a non-empty summary, and for verifying phases the closed-vocabulary verdict and every
      relevant obligation or finding id. Return free-form prose as your phase output; do not emit a final
      JSON object, produced_outputs, or contract_version.
    """.trimIndent()
    val structuralRepairNote = correctiveRepairContext?.structuralRepairEvidence?.let { evidence ->
      "\nDeterministic syntax repair previously succeeded on this capture (delimiter-only; " +
        "original_digest=${evidence.originalDigest} repaired_digest=${evidence.repairedDigest} " +
        "source=${evidence.sourceLocation.sourceLabel}:" +
        "${evidence.sourceLocation.line}:${evidence.sourceLocation.column}). " +
        "That does not mean the phase accepted it; correct the named violation in prose."
    } ?: if (correctiveRepairContext?.acceptedAfterStructuralRepair == true) {
      "\nDeterministic syntax repair previously succeeded on this capture (delimiter-only). " +
        "That does not mean the phase accepted it; correct the named violation in prose."
    } else {
      ""
    }
    val repairProjection = correctiveRepairContext?.let { context ->
      "\n\n" + context.promptProjection().renderAuthorizedRepairSection()
    }.orEmpty()
    return base + structuralRepairNote + repairProjection +
      unparseableRootCorrection(priorSchemaFailure) +
      FeatureTaskRuntimeSchemaFailureCorrections.lengthViolation(priorSchemaFailure) +
      FeatureTaskRuntimeSchemaFailureCorrections.closedEnumeration(priorSchemaFailure)
  }

  private fun unparseableRootCorrection(priorSchemaFailure: String): String {
    val rootNotParseable = priorSchemaFailure.contains("<root> must be an object") ||
      priorSchemaFailure.contains("Phase output is malformed")
    if (!rootNotParseable) {
      return ""
    }
    return "\nThe runtime could NOT parse a single JSON object out of your previous output — you likely " +
      "answered with prose, a Markdown table, or a JSON array. Restate the same facts in plain prose " +
      "with the closed vocabulary tokens the phase expects."
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
  ): String {
    val label = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepLabels[phaseId] ?: phaseId
    return buildString {
      appendLine("You are executing exactly one phase of the EXPERIMENTAL skill-bill feature-task-runtime")
      appendLine("loop ($forwardPhaseOrder)")
      appendLine("for issue $issueKey. The runtime owns the loop; do not run other phases, do not open")
      appendLine("or continue any other skill-bill workflow, and do not call `skill-bill workflow continue`.")
      appendLine()
      appendLine("Phase: $phaseId ($label)")
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

  private fun validationGateFindingsDirective(phaseId: String, findings: ValidationFindingSetProjection?): String {
    if (findings == null) return ""
    val (sectionTitle, preamble) = when (phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE -> Pair(
        "## Runtime validation gate findings",
        "A prior gate run parsed these items. They are the full open set for this repair turn — fix " +
          "every one in this session (shared root causes may collapse several into one change). Invoke " +
          "bill-code-check / the pack collect-all command only as needed to understand failures; do not " +
          "run `skill-bill validate`, `npx agnix`, or `scripts/validate_agent_configs`. Do not spawn " +
          "delegated subagents.",
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

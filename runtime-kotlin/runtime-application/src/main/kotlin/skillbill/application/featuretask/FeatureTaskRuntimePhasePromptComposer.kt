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
    priorSettlementFailure: String? = null,
    priorTerminalFailure: String? = null,
    priorFindingCoverage: String? = null,
    priorDerivationReask: String? = null,
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
      priorSettlementFailure = priorSettlementFailure,
      priorTerminalFailure = priorTerminalFailure,
      priorFindingCoverage = priorFindingCoverage,
    )
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
      gateRepairProseDirective(briefing.phaseId, validationGateFindings),
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
      implementationContinuationDirective(briefing.phaseId, implementationContinuation),
      retryCorrectionDirective(priorSettlementFailure),
      terminalRetryDirective(priorTerminalFailure),
      findingCoverageDirective(priorFindingCoverage),
      derivationReaskDirective(priorDerivationReask),
    ).filter(String::isNotBlank).joinToString(separator = "\n\n")
  }

  internal fun composeAgentPhaseInput(
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    inputs: PhaseTaskDirectiveInputs = PhaseTaskDirectiveInputs(),
  ): AgentPhaseInput {
    val input = briefing.briefingText.trim()
    require(input.isNotBlank()) { "Phase briefing text must be non-blank to compose AgentPhaseInput." }
    val requestedAction = phaseRequestedAction(
      phaseId = briefing.phaseId,
      inputs = inputs.copy(priorGapMemory = briefing.priorGapMemory),
    )
    require(requestedAction.isNotBlank()) { "Phase requestedAction must be non-blank." }
    return AgentPhaseInput(input = input, requestedAction = requestedAction)
  }

  fun frameAgentPhaseLaunchPrompt(phaseInput: AgentPhaseInput, directiveSections: String): String = buildString {
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
    priorSettlementFailure: String?,
    @Suppress("UNUSED_PARAMETER") priorTerminalFailure: String?,
    priorFindingCoverage: String?,
  ) {
    require(issueKey.isNotBlank()) { "issueKey is required to compose a phase prompt." }
    require(priorFindingCoverage.isNullOrBlank() || priorSettlementFailure.isNullOrBlank()) {
      "priorFindingCoverage cannot accompany a settlement failure; a receipt is either short of its " +
        "carried findings or could not settle, never both in one correction."
    }
  }

  private fun gateRepairProseDirective(phaseId: String, findings: ValidationFindingSetProjection?): String {
    if (findings == null) return ""
    if (
      phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE &&
      phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
    ) {
      return ""
    }
    return gateRepairProseDirective(phaseId)
  }

  private fun gateRepairProseDirective(phaseId: String): String = """
    ## Gate repair — prose only
    This launch is a repair turn for the runtime-owned `$phaseId` gate. Fix the listed findings at
    their root cause in this single session. Do not spawn delegated subagents. Do not suppress
    findings. When the fixes look done, stop and return prose only.
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

  // Emitted when the prior attempt at this phase could not settle (reason threaded in by the runner's
  // fix loop). Surfacing the reason turns each retry into a corrective attempt. Empty on the first
  // attempt, so a forward launch's prompt stays byte-for-byte unchanged.
  private fun retryCorrectionDirective(priorSettlementFailure: String?): String {
    if (priorSettlementFailure.isNullOrBlank()) {
      return ""
    }
    return """
      ## Previous attempt could not settle — restate in prose
      The runtime could not derive a decisive outcome from the previous output. Reason:
      $priorSettlementFailure
      Restate your phase outcome in plain prose inside your returned text. State status (completed, blocked,
      or failed), a non-empty summary, and for verifying phases the closed-vocabulary verdict and every
      relevant obligation or finding id. Return free-form prose as your phase output; do not wrap the
      outcome in structured JSON.
    """.trimIndent()
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
    @Suppress("UNUSED_PARAMETER") agentRunValidateFallback: Boolean = false,
    @Suppress("UNUSED_PARAMETER") packCollectAllCommand: String? = null,
    @Suppress("UNUSED_PARAMETER") packBuildCommand: String? = null,
    @Suppress("UNUSED_PARAMETER") priorGapMemory: FeatureTaskRuntimePriorGapMemory? = null,
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

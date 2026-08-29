package skillbill.application.featuretask

import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.featuretask.model.FeatureTaskRuntimeImplementationContinuation
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorReviewContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedger

/**
 * Pure composer of the full prompt a feature-task-runtime phase agent receives. The persisted
 * per-phase briefing is the durable handoff record; this prompt is the delivered copy of it,
 * framed with the phase task directive and the phase-output contract the schema gate enforces.
 * Without this delivery the agent would receive the default goal-continuation prompt and could
 * never produce schema-valid phase output.
 */
object FeatureTaskRuntimePhasePromptComposer {
  @Suppress(
    "LongParameterList",
    "LongMethod",
  )
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
    correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext? = null,
    operatorBlockRetry: FeatureTaskRuntimeOperatorBlockRetry? = null,
    implementationContinuation: FeatureTaskRuntimeImplementationContinuation? = null,
    validationGateFindings: ValidationFindingSetProjection? = null,
    validationGateTriagePlan: String? = null,
    validationGateRepair: Boolean = false,
    validationGateTriage: Boolean = false,
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
    val effectiveContinuation = implementationContinuation.takeUnless { correctiveRepairContext != null }
    return listOf(
      phasePromptHeader(
        issueKey,
        briefing.phaseId,
        agentRunValidateFallback,
        packCollectAllCommand,
        packBuildCommand,
        briefing.priorGapMemory,
        validationGateRepair = validationGateRepair,
        validationGateTriage = validationGateTriage,
      ),
      installedRuntimeAuthorityDirective(),
      ceremonyDirective(briefing),
      mutatingPhaseIdempotencyDirective(briefing.phaseId),
      nonValidatePhaseValidationOwnershipDirective(briefing.phaseId),
      nonBuildPhaseBuildOwnershipDirective(briefing.phaseId),
      minimalismDisciplineDirective(briefing.phaseId),
      testValueDisciplineDirective(briefing.phaseId),
      priorGapMemoryRemediationDirective(briefing.phaseId, briefing.priorGapMemory),
      goalContinuationDirective(briefing.phaseId, suppressDecomposition),
      absentValidationGateDegradationDirective(briefing.phaseId, agentRunValidateFallback),
      validationGateFindingsDirective(
        briefing.phaseId,
        validationGateFindings,
        validationGateTriagePlan,
      ),
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
      briefing.briefingText,
      operatorBlockRetryDirective(briefing.phaseId, operatorBlockRetry),
      implementationContinuationDirective(briefing.phaseId, effectiveContinuation),
      retryCorrectionDirective(briefing, priorSchemaFailure, correctiveRepairContext),
      terminalRetryDirective(priorTerminalFailure),
      findingCoverageDirective(priorFindingCoverage),
      if (validationGateFindings != null) {
        gateRepairNoOutputSchemaDirective(briefing.phaseId, validationGateTriage)
      } else {
        outputContract(briefing, agentRunValidateFallback)
      },
    ).filter(String::isNotBlank).joinToString(separator = "\n\n")
  }

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
}

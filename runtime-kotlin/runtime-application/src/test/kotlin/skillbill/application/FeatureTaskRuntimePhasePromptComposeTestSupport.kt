package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimePhasePromptComposer
import skillbill.application.featuretask.model.FeatureTaskRuntimeImplementationContinuation
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.featuretask.model.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorReviewContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedger

@Suppress("LongParameterList")
internal fun composePhasePrompt(
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
): String = FeatureTaskRuntimePhasePromptComposer.compose(
  FeatureTaskRuntimePhasePromptComposeInputs(
    issueKey = issueKey,
    briefing = briefing,
    suppressDecomposition = suppressDecomposition,
    codeReviewMode = codeReviewMode,
    reviewPassNumber = reviewPassNumber,
    goalSubtaskReviewInput = goalSubtaskReviewInput,
    baselineUntrackedPaths = baselineUntrackedPaths,
    resolvedReviewTier = resolvedReviewTier,
    reviewDecidingRule = reviewDecidingRule,
    priorSchemaFailure = priorSchemaFailure,
    priorTerminalFailure = priorTerminalFailure,
    priorFindingCoverage = priorFindingCoverage,
    correctiveRepairContext = correctiveRepairContext,
    operatorBlockRetry = operatorBlockRetry,
    implementationContinuation = implementationContinuation,
    validationGateFindings = validationGateFindings,
    validationGateTriagePlan = validationGateTriagePlan,
    validationGateRepair = validationGateRepair,
    validationGateTriage = validationGateTriage,
    agentRunValidateFallback = agentRunValidateFallback,
    packCollectAllCommand = packCollectAllCommand,
    packBuildCommand = packBuildCommand,
    repairLedger = repairLedger,
    priorReviewContext = priorReviewContext,
  ),
)

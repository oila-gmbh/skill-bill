package skillbill.application.featuretask.model

import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.review.context.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorReviewContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedger

data class FeatureTaskRuntimePhasePromptComposeInputs(
  val issueKey: String,
  val briefing: FeatureTaskRuntimePhaseLaunchBriefing,
  val suppressDecomposition: Boolean = false,
  val codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.DEFAULT,
  val reviewPassNumber: Int? = null,
  val goalSubtaskReviewInput: GoalSubtaskReviewInput? = null,
  val baselineUntrackedPaths: List<String> = emptyList(),
  val resolvedReviewTier: CodeReviewExecutionMode? = null,
  val reviewDecidingRule: String? = null,
  val priorSchemaFailure: String? = null,
  val priorTerminalFailure: String? = null,
  val priorFindingCoverage: String? = null,
  val correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext? = null,
  val operatorBlockRetry: FeatureTaskRuntimeOperatorBlockRetry? = null,
  val implementationContinuation: FeatureTaskRuntimeImplementationContinuation? = null,
  val validationGateFindings: ValidationFindingSetProjection? = null,
  val validationGateTriagePlan: String? = null,
  val validationGateRepair: Boolean = false,
  val validationGateTriage: Boolean = false,
  val agentRunValidateFallback: Boolean = false,
  val packCollectAllCommand: String? = null,
  val packBuildCommand: String? = null,
  val repairLedger: FeatureTaskRuntimeRepairLedger? = null,
  val priorReviewContext: FeatureTaskRuntimePriorReviewContext? = null,
  val phaseSettlement: FeatureTaskRuntimePhaseSettlementTarget? = null,
)

package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeImplementationContinuation
import skillbill.application.featuretask.model.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.application.featuretask.model.PhasePromptHeaderInputs

internal fun phasePromptLeadingSections(inputs: FeatureTaskRuntimePhasePromptComposeInputs): List<String> {
  val auditGapImplement = isAuditGapImplement(inputs.briefing)
  return listOf(
    phasePromptHeader(
      PhasePromptHeaderInputs(
        issueKey = inputs.issueKey,
        phaseId = inputs.briefing.phaseId,
        agentRunValidateFallback = inputs.agentRunValidateFallback,
        packCollectAllCommand = inputs.packCollectAllCommand,
        packBuildCommand = inputs.packBuildCommand,
        priorGapMemory = inputs.briefing.priorGapMemory,
        validationGateRepair = inputs.validationGateRepair,
        validationGateTriage = inputs.validationGateTriage,
        acceptanceCriteria = inputs.briefing.acceptanceCriteria,
        auditGapImplement = auditGapImplement,
      ),
    ),
    installedRuntimeAuthorityDirective(),
    ceremonyDirective(inputs.briefing),
    mutatingPhaseIdempotencyDirective(inputs.briefing.phaseId),
    nonValidatePhaseValidationOwnershipDirective(
      inputs.briefing.phaseId,
      inputs.briefing.acceptanceCriteria,
      auditGapImplement,
    ),
    nonBuildPhaseBuildOwnershipDirective(
      inputs.briefing.phaseId,
      inputs.briefing.acceptanceCriteria,
      auditGapImplement,
    ),
    minimalismDisciplineDirective(inputs.briefing.phaseId),
    testValueDisciplineDirective(inputs.briefing.phaseId),
    priorGapMemoryRemediationDirective(inputs.briefing.phaseId, inputs.briefing.priorGapMemory),
  )
}

internal fun phasePromptMiddleSections(inputs: FeatureTaskRuntimePhasePromptComposeInputs): List<String> = listOf(
  goalContinuationDirective(inputs.briefing.phaseId, inputs.suppressDecomposition),
  absentValidationGateDegradationDirective(inputs.briefing.phaseId, inputs.agentRunValidateFallback),
  validationGateFindingsDirective(
    inputs.briefing.phaseId,
    inputs.validationGateFindings,
    inputs.validationGateTriagePlan,
  ),
  reviewExecutionDirective(
    inputs.briefing.phaseId,
    ReviewExecutionDirectiveInputs(
      codeReviewMode = inputs.codeReviewMode,
      goalSubtaskReviewInput = inputs.goalSubtaskReviewInput,
      reviewPassNumber = inputs.reviewPassNumber,
      resolvedReviewTier = inputs.resolvedReviewTier,
      reviewDecidingRule = inputs.reviewDecidingRule,
      baselineUntrackedPaths = inputs.baselineUntrackedPaths,
      repairLedger = inputs.repairLedger,
      priorReviewContext = inputs.priorReviewContext,
    ),
  ),
  commitExclusionDirective(inputs.briefing.phaseId, inputs.issueKey),
  inputs.briefing.briefingText,
)

internal fun phasePromptTrailingSections(
  inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  effectiveContinuation: FeatureTaskRuntimeImplementationContinuation?,
): List<String> = listOf(
  operatorBlockRetryDirective(inputs.briefing.phaseId, inputs.operatorBlockRetry),
  implementationContinuationDirective(inputs.briefing.phaseId, effectiveContinuation),
  retryCorrectionDirective(inputs.briefing, inputs.priorSchemaFailure, inputs.correctiveRepairContext),
  terminalRetryDirective(inputs.priorTerminalFailure),
  findingCoverageDirective(inputs.priorFindingCoverage),
  if (inputs.validationGateFindings != null) {
    gateRepairNoOutputSchemaDirective(inputs.briefing.phaseId, inputs.validationGateTriage)
  } else {
    outputContract(inputs.briefing, inputs.agentRunValidateFallback)
  },
)

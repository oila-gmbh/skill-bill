package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeBriefingProjectionInputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionInputs

internal fun briefingProjectionInputs(
  inputs: FeatureTaskRuntimeBriefingProjectionInputs,
): FeatureTaskRuntimeHandoffProjectionInputs = FeatureTaskRuntimeHandoffProjectionInputs(
  consumerPhaseId = inputs.handoff.phaseId,
  declarations = inputs.declarations,
  resolvedUpstream = inputs.handoff.upstreamOutputs,
  runInvariants = inputs.handoff.runInvariants,
  resolvedCheckpoint = inputs.handoff.repositoryCheckpoint,
  sharedReviewEvidence = inputs.sharedReviewEvidence,
  expectedCheckpoint = inputs.handoff.expectedRepositoryCheckpoint,
  priorGapMemory = inputs.handoff.priorGapMemory,
  repairLedger = inputs.handoff.repairLedger,
  recordedFindingVerdicts = inputs.handoff.recordedFindingVerdicts,
  branchIdentity = inputs.handoff.branchIdentity,
  baseBranch = inputs.handoff.baseBranch,
  workflowId = inputs.workflowId,
  planningProjectionValidator = inputs.planningProjectionValidator,
  addonContentBySlug = inputs.addonContentBySlug,
  validationDepth = inputs.handoff.validationDepth,
  qualityGateSelection = inputs.handoff.qualityGateSelection,
)

package skillbill.application.featuretask.model

import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedReviewEvidenceReference
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseHandoff
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration

internal data class FeatureTaskRuntimeBriefingProjectionInputs(
  val handoff: FeatureTaskRuntimePhaseHandoff,
  val declarations: List<PhaseHandoffProjectionDeclaration>,
  val workflowId: String?,
  val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
  val sharedReviewEvidence: FeatureTaskRuntimeSharedReviewEvidenceReference?,
  val addonContentBySlug: Map<String, String>,
)

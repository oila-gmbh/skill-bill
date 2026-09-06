package skillbill.application.featuretask.model
import skillbill.workflow.engine.model.WorkflowId
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseHandoff
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedReviewEvidenceReference
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration

data class FeatureTaskRuntimeBriefingProjectionInputs(
  val handoff: FeatureTaskRuntimePhaseHandoff,
  val declarations: List<PhaseHandoffProjectionDeclaration>,
  val workflowId: WorkflowId?,
  val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
  val sharedReviewEvidence: FeatureTaskRuntimeSharedReviewEvidenceReference?,
  val addonContentBySlug: Map<String, String>,
)

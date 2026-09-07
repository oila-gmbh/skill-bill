package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap

/**
 * One silent heal of a goal-continuation field on resume: the launcher value was adopted because the
 * durable row never recorded the field. Observability policy requires every silent heal to leave
 * durable evidence of what changed and why.
 */
data class FeatureTaskRuntimeGoalContinuationFieldAdoption(
  val field: String,
  val adoptedValue: String,
  val reason: String,
) {
  init {
    require(field.isNotBlank()) {
      "FeatureTaskRuntimeGoalContinuationFieldAdoption.field must be non-blank."
    }
    require(adoptedValue.isNotBlank()) {
      "FeatureTaskRuntimeGoalContinuationFieldAdoption.adoptedValue must be non-blank."
    }
    require(reason.isNotBlank()) {
      "FeatureTaskRuntimeGoalContinuationFieldAdoption.reason must be non-blank."
    }
  }

  @OpenBoundaryMap("Goal-continuation field-adoption evidence at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "field" to field,
    "adopted_value" to adoptedValue,
    "reason" to reason,
  )

  companion object {
    @OpenBoundaryMap("Goal-continuation field-adoption decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationFieldAdoption =
      FeatureTaskRuntimeGoalContinuationFieldAdoption(
        field = raw.requireStringField("field"),
        adoptedValue = raw.requireStringField("adopted_value"),
        reason = raw.requireStringField("reason"),
      )
  }
}

data class FeatureTaskRuntimeGoalPlanningImport(
  val parentGoalWorkflowId: String,
  val normalizedIssueKey: String,
  val repositoryIdentity: String,
  val parentSpecHash: String,
  val decompositionManifestHash: String,
  val planningContractId: String,
  val planningContractVersion: String,
  val phaseOutputContractId: String,
  val phaseOutputContractVersion: String,
  val subtaskId: Int,
  val manifestOrder: Int,
  val governedSubSpecPath: String,
  val subSpecHash: String,
  val preplanPayloadSha256: String,
  val planPayloadSha256: String,
) {
  @OpenBoundaryMap("Validated goal-planning import provenance at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "source_kind" to "imported_goal_planning",
    "parent_goal_workflow_id" to parentGoalWorkflowId,
    "normalized_issue_key" to normalizedIssueKey,
    "repository_identity" to repositoryIdentity,
    "parent_spec_hash" to parentSpecHash,
    "decomposition_manifest_hash" to decompositionManifestHash,
    "planning_contract_id" to planningContractId,
    "planning_contract_version" to planningContractVersion,
    "phase_output_contract_id" to phaseOutputContractId,
    "phase_output_contract_version" to phaseOutputContractVersion,
    "subtask_id" to subtaskId,
    "manifest_order" to manifestOrder,
    "governed_sub_spec_path" to governedSubSpecPath,
    "sub_spec_hash" to subSpecHash,
    "preplan_payload_sha256" to preplanPayloadSha256,
    "plan_payload_sha256" to planPayloadSha256,
  )
}

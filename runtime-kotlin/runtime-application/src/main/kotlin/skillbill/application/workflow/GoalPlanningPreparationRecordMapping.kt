package skillbill.application.workflow

import skillbill.ports.persistence.model.GoalPlanningPreparationProvenance
import skillbill.ports.persistence.model.GoalPlanningPreparationRecord
import skillbill.ports.persistence.model.GoalPlanningPreparationState

internal fun GoalPlanningPreparationRecord.toEnvelopeMap(): Map<String, Any?> = linkedMapOf(
  "contract_version" to contractVersion,
  "parent_goal_workflow_id" to parentGoalWorkflowId,
  "normalized_issue_key" to normalizedIssueKey,
  "repository_identity" to repositoryIdentity,
  "subtask_id" to subtaskId,
  "governed_sub_spec_path" to governedSubSpecPath,
  "preparation_status" to preparationStatus.wireValue,
  "provenance" to linkedMapOf(
    "parent_spec_hash" to provenance.parentSpecHash,
    "sub_spec_hash" to provenance.subSpecHash,
    "decomposition_manifest_hash" to provenance.decompositionManifestHash,
    "phase_output_contract_id" to provenance.phaseOutputContractId,
    "phase_output_contract_version" to provenance.phaseOutputContractVersion,
  ),
  "preplan_payload" to preplanPayload,
  "plan_payload" to planPayload,
)

internal fun Map<String, Any?>.toGoalPlanningPreparationRecord(): GoalPlanningPreparationRecord {
  val provenanceMap = (this["provenance"] as? Map<*, *>).orEmpty()
  return GoalPlanningPreparationRecord(
    parentGoalWorkflowId = stringValue("parent_goal_workflow_id"),
    normalizedIssueKey = stringValue("normalized_issue_key"),
    repositoryIdentity = stringValue("repository_identity"),
    subtaskId = (this["subtask_id"] as Number).toInt(),
    governedSubSpecPath = stringValue("governed_sub_spec_path"),
    preparationStatus = GoalPlanningPreparationState.fromWireValue(stringValue("preparation_status")),
    provenance = GoalPlanningPreparationProvenance(
      parentSpecHash = provenanceMap.stringEntry("parent_spec_hash"),
      subSpecHash = provenanceMap.stringEntry("sub_spec_hash"),
      decompositionManifestHash = provenanceMap.stringEntry("decomposition_manifest_hash"),
      phaseOutputContractId = provenanceMap.stringEntry("phase_output_contract_id"),
      phaseOutputContractVersion = provenanceMap.stringEntry("phase_output_contract_version"),
    ),
    preplanPayload = stringValue("preplan_payload"),
    planPayload = stringValue("plan_payload"),
    contractVersion = stringValue("contract_version"),
  )
}

private fun Map<String, Any?>.stringValue(key: String): String = this[key]?.toString().orEmpty()

private fun Map<*, *>.stringEntry(key: String): String = this[key]?.toString().orEmpty()

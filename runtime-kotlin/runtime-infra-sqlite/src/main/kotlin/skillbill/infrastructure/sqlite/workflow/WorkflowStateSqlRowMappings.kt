package skillbill.db.workflow

import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.ports.workflow.model.WorkflowStateRecord
import java.sql.ResultSet

internal fun ResultSet.toWorkflowStateRecord(): WorkflowStateRecord = WorkflowStateRecord(
  workflowId = getString("workflow_id"),
  sessionId = getString("session_id"),
  workflowName = getString("workflow_name"),
  contractVersion = getString("contract_version"),
  workflowStatus = getString("workflow_status"),
  currentStepId = getString("current_step_id"),
  stepsJson = getString("steps_json"),
  artifactsJson = getString("artifacts_json"),
  issueKey = getString("issue_key"),
  startedAt = getString("started_at"),
  updatedAt = getString("updated_at"),
  stateEnteredAt = getString("state_entered_at"),
  stateEnteredAtEstimated = getInt("state_entered_at_estimated") != 0,
  finishedAt = getString("finished_at"),
)

internal fun ResultSet.toFeatureTaskWorkflowStateRecord(): WorkflowStateRecord {
  val workflowId = getString("workflow_id")
  val workflowName = getString("workflow_name")
  if (workflowName != "bill-feature-task") {
    throw InvalidWorkflowStateSchemaError(
      "Feature-task workflow '$workflowId' must persist workflow_name='bill-feature-task'; found '$workflowName'.",
    )
  }
  val rawMode = getString("mode")
  val mode = FeatureTaskWorkflowMode.fromWireValue(rawMode)
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task workflow '$workflowId' has unknown mode '$rawMode'.",
    )
  return WorkflowStateRecord(
    workflowId = workflowId,
    sessionId = getString("session_id"),
    workflowName = workflowName,
    contractVersion = getString("contract_version"),
    workflowStatus = getString("workflow_status"),
    currentStepId = getString("current_step_id"),
    stepsJson = getString("steps_json"),
    artifactsJson = getString("artifacts_json"),
    issueKey = getString("issue_key"),
    startedAt = getString("started_at"),
    updatedAt = getString("updated_at"),
    stateEnteredAt = getString("state_entered_at"),
    stateEnteredAtEstimated = getInt("state_entered_at_estimated") != 0,
    finishedAt = getString("finished_at"),
    mode = mode,
    implementationSkill = getString("implementation_skill"),
  )
}

internal fun ResultSet.toFeatureTaskWorkflowSnapshotRecord(): WorkflowStateRecord = WorkflowStateRecord(
  workflowId = getString("workflow_id"),
  sessionId = getString("session_id"),
  workflowName = getString("workflow_name"),
  contractVersion = getString("contract_version"),
  workflowStatus = getString("workflow_status"),
  currentStepId = getString("current_step_id"),
  stepsJson = getString("steps_json"),
  artifactsJson = getString("artifacts_json"),
  issueKey = getString("issue_key"),
  startedAt = getString("started_at"),
  updatedAt = getString("updated_at"),
  stateEnteredAt = getString("state_entered_at"),
  stateEnteredAtEstimated = getInt("state_entered_at_estimated") != 0,
  finishedAt = getString("finished_at"),
  mode = FeatureTaskWorkflowMode.fromWireValue(getString("mode")),
  implementationSkill = getString("implementation_skill"),
)

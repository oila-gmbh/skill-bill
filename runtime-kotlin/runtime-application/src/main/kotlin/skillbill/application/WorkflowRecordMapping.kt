package skillbill.application

import skillbill.ports.persistence.model.FeatureImplementSessionSummary
import skillbill.ports.persistence.model.FeatureVerifySessionSummary
import skillbill.ports.persistence.model.WorkflowStateRecord
import skillbill.workflow.model.WorkflowStateSnapshot

internal fun WorkflowStateRecord.toSnapshot(): WorkflowStateSnapshot = WorkflowStateSnapshot(
  workflowId = workflowId,
  sessionId = sessionId,
  workflowName = workflowName,
  contractVersion = contractVersion,
  workflowStatus = workflowStatus,
  currentStepId = currentStepId,
  stepsJson = stepsJson,
  artifactsJson = artifactsJson,
  startedAt = startedAt,
  updatedAt = updatedAt,
  finishedAt = finishedAt,
)

internal fun WorkflowStateSnapshot.toRecord(): WorkflowStateRecord = WorkflowStateRecord(
  workflowId = workflowId,
  sessionId = sessionId,
  workflowName = workflowName,
  contractVersion = contractVersion,
  workflowStatus = workflowStatus,
  currentStepId = currentStepId,
  stepsJson = stepsJson,
  artifactsJson = artifactsJson,
  startedAt = startedAt,
  updatedAt = updatedAt,
  finishedAt = finishedAt,
)

internal fun FeatureImplementSessionSummary.toPayload(): Map<String, Any?> = linkedMapOf(
  "session_id" to sessionId,
  "issue_key_provided" to issueKeyProvided,
  "issue_key_type" to issueKeyType,
  "spec_input_types" to specInputTypes,
  "spec_word_count" to specWordCount,
  "feature_size" to featureSize,
  "feature_name" to featureName,
  "rollout_needed" to rolloutNeeded,
  "acceptance_criteria_count" to acceptanceCriteriaCount,
  "open_questions_count" to openQuestionsCount,
  "spec_summary" to specSummary,
)

internal fun FeatureVerifySessionSummary.toPayload(): Map<String, Any?> = linkedMapOf(
  "session_id" to sessionId,
  "acceptance_criteria_count" to acceptanceCriteriaCount,
  "rollout_relevant" to rolloutRelevant,
  "spec_summary" to specSummary,
)

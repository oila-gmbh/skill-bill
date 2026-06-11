package skillbill.application

import skillbill.ports.persistence.model.FeatureImplementSessionSummary
import skillbill.ports.persistence.model.FeatureVerifySessionSummary
import skillbill.ports.persistence.model.WorkflowStateRecord
import skillbill.workflow.model.WorkflowStateSnapshot

/**
 * SKILL-48 Subtask 2a: `toSnapshot` is a pure record-to-snapshot
 * mapping helper. The canonical workflow-state schema validator runs
 * at the in-process construction sites (`WorkflowEngine.openRecord` /
 * `updateRecord`) and at every read seam (`fullPayload` /
 * `summaryPayload`), which together cover every WorkflowService
 * caller (open / update / get / list / latest / resume / continue).
 * Keeping the validator out of this mapping helper avoids running the
 * network-y schema engine on raw DB rows; the next call into
 * WorkflowEngine validates the shape downstream.
 *
 * Backward-compatibility note: this mapping is INTENTIONALLY not
 * validating. Legacy durable records that drifted from the current
 * per-skill enums (e.g. an obsolete `workflow_status` or a removed
 * `step_id` after a definition change) pass through `toSnapshot`
 * untouched and then loud-fail with `InvalidWorkflowStateSchemaError`
 * at the next `WorkflowEngine` read seam. There is no compatibility
 * shim by design — see the runtime-contract backward-compatibility
 * paragraph in `AGENTS.md` for the operator recovery story.
 */
internal fun WorkflowStateRecord.toSnapshot(): WorkflowStateSnapshot = WorkflowStateSnapshot(
  workflowId = workflowId,
  sessionId = sessionId,
  workflowName = workflowName,
  mode = mode?.wireValue,
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
  mode = mode?.let(skillbill.ports.persistence.model.FeatureTaskWorkflowMode::fromWireValue),
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

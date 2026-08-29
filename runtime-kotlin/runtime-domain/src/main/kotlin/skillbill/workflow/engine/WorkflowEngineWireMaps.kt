package skillbill.workflow.engine

import skillbill.contracts.workflow.WorkflowContracts
import skillbill.workflow.engine.model.WorkflowCompactContinueView
import skillbill.workflow.engine.model.WorkflowContinuationArtifactSummary
import skillbill.workflow.engine.model.WorkflowContinueView
import skillbill.workflow.engine.model.WorkflowInputProjection
import skillbill.workflow.engine.model.WorkflowResumeView
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowSummaryView
import skillbill.workflow.engine.model.WorkflowUpdateAcknowledgementView

internal object WorkflowEngineWireMaps {
  fun snapshotMap(view: WorkflowSnapshotView): Map<String, Any?> = WorkflowContracts.fullWorkflowPayload(
    linkedMapOf(
      "workflow_id" to view.workflowId,
      "session_id" to view.sessionId,
      "workflow_name" to view.workflowName,
      "mode" to view.mode,
      "contract_version" to view.contractVersion,
      "workflow_status" to view.workflowStatus,
      "current_step_id" to view.currentStepId,
      "steps" to view.steps.map(::workflowStepMap),
      "artifacts" to view.artifacts,
      "started_at" to view.startedAt,
      "updated_at" to view.updatedAt,
      "finished_at" to view.finishedAt,
    ),
  )

  fun summaryMap(view: WorkflowSummaryView): Map<String, Any?> = WorkflowContracts.summaryWorkflowPayload(
    linkedMapOf(
      "workflow_id" to view.workflowId,
      "session_id" to view.sessionId,
      "workflow_name" to view.workflowName,
      "mode" to view.mode,
      "contract_version" to view.contractVersion,
      "workflow_status" to view.workflowStatus,
      "current_step_id" to view.currentStepId,
      "started_at" to view.startedAt,
      "updated_at" to view.updatedAt,
      "finished_at" to view.finishedAt,
    ),
  )

  fun resumeMap(view: WorkflowResumeView): Map<String, Any?> = WorkflowContracts.resumePayload(
    snapshotMap(view.snapshot),
    linkedMapOf(
      "resume_mode" to view.resumeMode,
      "resume_step_id" to view.resumeStepId,
      "last_completed_step_id" to view.lastCompletedStepId,
      "available_artifacts" to view.availableArtifacts,
      "required_artifacts" to view.requiredArtifacts,
      "missing_artifacts" to view.missingArtifacts,
      "can_resume" to view.canResume,
      "next_action" to view.nextAction,
    ),
  )

  fun continueMap(view: WorkflowContinueView): Map<String, Any?> = WorkflowContracts.continuePayload(
    resumeMap(view.resume),
    linkedMapOf(
      "skill_name" to view.skillName,
      "workflow_status_before_continue" to view.workflowStatusBeforeContinue,
      "continue_status" to view.continueStatus,
      "continue_step_id" to view.continueStepId,
      "continue_step_label" to view.continueStepLabel,
      "continue_step_directive" to view.continueStepDirective,
      "reference_sections" to view.referenceSections,
      "step_artifact_keys" to view.stepArtifactKeys,
      "step_artifacts" to view.stepArtifacts,
      "session_summary" to view.sessionSummary,
      "continuation_brief" to view.continuationBrief,
      "continuation_entry_prompt" to view.continuationEntryPrompt,
      "extra_fields" to view.extraFields,
    ),
  )

  fun compactContinueMap(view: WorkflowCompactContinueView): Map<String, Any?> = linkedMapOf(
    "workflow_id" to view.workflowId,
    "skill_name" to view.skillName,
    "workflow_status_before_continue" to view.workflowStatusBeforeContinue,
    "started_at" to view.startedAt,
    "updated_at" to view.updatedAt,
    "continue_status" to view.continueStatus,
    "resume_step_id" to view.resumeStepId,
    "resume_step_label" to view.resumeStepLabel,
    "continue_step_id" to view.resumeStepId,
    "continue_step_label" to view.resumeStepLabel,
    "continue_step_directive" to view.continueStepDirective,
    "reference_sections" to view.referenceSections,
    "required_artifact_keys" to view.requiredArtifactKeys,
    "available_artifact_keys" to view.availableArtifactKeys,
    "missing_artifact_keys" to view.missingArtifactKeys,
    "required_artifacts" to view.requiredArtifactKeys,
    "available_artifacts" to view.availableArtifactKeys,
    "missing_artifacts" to view.missingArtifactKeys,
    "current_step_artifacts" to view.currentStepArtifacts.map(::artifactSummaryMap),
    "omitted_artifact_keys" to view.omittedArtifactKeys,
    "continuation_brief" to view.continuationBrief,
    "continuation_entry_prompt" to view.continuationEntryPrompt,
    "read_only_full_state_guidance" to view.readOnlyFullStateGuidance,
  )

  fun updateAcknowledgementMap(view: WorkflowUpdateAcknowledgementView): Map<String, Any?> = linkedMapOf(
    "status" to view.status,
    "workflow_id" to view.workflowId,
    "workflow_name" to view.workflowName,
    "workflow_status" to view.workflowStatus,
    "current_step_id" to view.currentStepId,
    "updated_step_ids" to view.updatedStepIds,
    "updated_artifact_keys" to view.updatedArtifactKeys,
    "read_only_full_state_guidance" to view.readOnlyFullStateGuidance,
  )

  fun inputProjectionMap(projection: WorkflowInputProjection): Map<String, Any?> = linkedMapOf(
    "step_id" to projection.stepId,
    "producer_iteration" to projection.producerIteration,
    "repository_checkpoint" to projection.repositoryCheckpoint,
    "artifacts" to projection.artifacts,
    "utf8_bytes" to projection.utf8Bytes,
  )

  fun artifactSummaryMap(summary: WorkflowContinuationArtifactSummary): Map<String, Any?> = linkedMapOf(
    "key" to summary.key,
    "present" to summary.present,
    "inline" to summary.inline,
    "size_bytes" to summary.sizeBytes,
    "value" to summary.value,
    "preview" to summary.preview,
    "truncated" to summary.truncated,
    "omitted" to summary.omitted,
    "omission_reason" to summary.omissionReason,
  )
}

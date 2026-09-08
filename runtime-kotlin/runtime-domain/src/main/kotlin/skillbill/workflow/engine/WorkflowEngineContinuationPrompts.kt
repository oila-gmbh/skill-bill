package skillbill.workflow.engine

import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

internal data class ContinuationArtifactKeys(
  val currentStepArtifactKeys: List<String>,
  val omittedArtifactKeys: List<String>,
)

internal data class ContinuationBriefRequest(
  val definition: WorkflowDefinition,
  val workflowId: String,
  val resumeStepId: String,
  val continueStatus: String,
  val nextAction: String,
  val artifactKeys: ContinuationArtifactKeys,
)

internal fun continuationBrief(request: ContinuationBriefRequest): String {
  val stepLabel = request.definition.stepLabels[request.resumeStepId] ?: request.resumeStepId
  val currentArtifacts = request.artifactKeys.currentStepArtifactKeys.joinToString().ifBlank { "none" }
  val omittedArtifacts = request.artifactKeys.omittedArtifactKeys.joinToString().ifBlank { "none" }
  val instructionPath = CONTINUATION_CONTENT_PATHS[request.definition.skillName]
    ?.let { path -> "Follow the normal step instructions in `$path`. " }
    .orEmpty()
  return "Resume `${request.definition.skillName}` workflow `${request.workflowId}` from `$stepLabel` " +
    "(`${request.resumeStepId}`). " +
    instructionPath +
    "Use `current_step_artifacts` in this compact payload ($currentArtifacts) as authoritative " +
    "current-step context instead of reconstructing prior context from chat history. " +
    "Omitted artifact keys ($omittedArtifacts) remain private phase context. Explicit operator diagnostics " +
    "may inspect them with `workflow show`; phase agents must not. Workflow activation status: " +
    "`${request.continueStatus}`. Next action: ${request.nextAction}"
}

internal data class ContinuationIdentity(
  val workflowId: String,
  val sessionId: String,
  val resumeStepId: String,
  val continueStatus: String,
  val nextAction: String,
  val nextAttemptCount: Int,
)

internal data class ContinuationEntryPromptRequest(
  val definition: WorkflowDefinition,
  val identity: ContinuationIdentity,
  val artifactKeys: ContinuationArtifactKeys,
  val sessionSummary: Map<String, Any?>,
  val extraFields: Map<String, Any?>,
)

internal fun continuationEntryPrompt(request: ContinuationEntryPromptRequest): String {
  val identity = request.identity
  val references = request.definition.continuationReferenceSections[identity.resumeStepId].orEmpty()
    .joinToString("; ")
  val directive =
    request.definition.continuationDirectives[identity.resumeStepId]
      ?: (
        "Resume the workflow from the recovered current step using the persisted artifacts as " +
          "authoritative context."
        )
  val currentArtifacts = request.artifactKeys.currentStepArtifactKeys.joinToString().ifBlank { "none" }
  val omittedArtifacts = request.artifactKeys.omittedArtifactKeys.joinToString().ifBlank { "none" }
  val commonLines =
    mutableListOf(
      "Use `${request.definition.skillName}` in continuation mode.",
      "Workflow id: ${identity.workflowId}",
      "Session id: ${identity.sessionId.ifBlank { "(none)" }}",
      "Continue status: ${identity.continueStatus}",
      "Resume step: ${identity.resumeStepId} " +
        "(${request.definition.stepLabels[identity.resumeStepId] ?: identity.resumeStepId})",
    )
  if (request.definition.workflowName == FeatureTaskRuntimePhaseWorkflowDefinition.definition.workflowName) {
    commonLines += "Feature: ${(request.extraFields["feature_name"] as String).ifBlank { "(unknown)" }}"
    commonLines += "Feature size: ${(request.extraFields["feature_size"] as String).ifBlank { "(unknown)" }}"
    commonLines += "Branch: ${(request.extraFields["branch_name"] as String).ifBlank { "(unknown)" }}"
  }
  commonLines += "Current-step artifacts: $currentArtifacts"
  commonLines += "Omitted artifact keys: $omittedArtifacts"
  if (request.definition.skillName == "bill-feature-verify") {
    commonLines += "Acceptance criteria count: ${request.sessionSummary["acceptance_criteria_count"] ?: 0}"
    commonLines += "Rollout relevant: ${request.sessionSummary["rollout_relevant"] ?: false}"
  }
  val specSummary = request.sessionSummary["spec_summary"]?.toString()?.ifBlank { "(none saved)" }
    ?: "(none saved)"
  commonLines += "Spec summary: $specSummary"
  commonLines += "Reference sections: ${references.ifBlank { "normal step instructions only" }}"
  commonLines +=
    "Rules: do not rerun completed steps unless the workflow sends work backwards; treat " +
    "`current_step_artifacts` as the complete authoritative phase input. Omitted keys remain private; " +
    "`workflow show` is an explicit operator diagnostic and must not widen phase context."
  commonLines +=
    "Workflow update rule: every step_updates item must include step_id, status, and integer " +
    "attempt_count; use attempt_count ${identity.nextAttemptCount} for `${identity.resumeStepId}` " +
    "unless a later retry increments it."
  commonLines += "Keep the same workflow_id and session_id, then continue `${request.definition.skillName}`."
  commonLines += "Step directive: $directive"
  commonLines += "Immediate next action: ${identity.nextAction}"
  return commonLines.joinToString("\n")
}

internal val CONTINUATION_CONTENT_PATHS: Map<String, String> = mapOf(
  "bill-feature-verify" to "skills/bill-feature-verify/content.md",
)

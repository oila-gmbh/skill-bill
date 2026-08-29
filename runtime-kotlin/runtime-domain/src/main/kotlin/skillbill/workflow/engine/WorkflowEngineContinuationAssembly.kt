package skillbill.workflow.engine

import skillbill.workflow.engine.model.ResolvedRequiredArtifact
import skillbill.workflow.engine.model.WorkflowCompactContinueView
import skillbill.workflow.engine.model.WorkflowContinuationArtifactSummary
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.engine.model.WorkflowInputProjection
import skillbill.workflow.engine.model.WorkflowResumeView
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowStepState
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

internal val workflowResumableStepStatuses = setOf("running", "blocked", "pending")

internal fun continueStatusFor(
  snapshot: WorkflowSnapshotView,
  resume: WorkflowResumeView,
  currentStep: WorkflowStepState?,
): String {
  val alreadyRunning =
    snapshot.workflowStatus == "running" &&
      snapshot.currentStepId == resume.resumeStepId &&
      currentStep?.status == "running"
  return when {
    resume.resumeMode == "done" -> "done"
    resume.canResume && alreadyRunning -> "already_running"
    resume.canResume -> "reopened"
    else -> "blocked"
  }
}

internal fun continueArtifactKeys(
  definition: WorkflowDefinition,
  resumeStepId: String,
  snapshot: WorkflowSnapshotView,
): List<String> {
  val keys = mutableListOf<String>()
  definition.continuationArtifactOrder.forEach { key ->
    if (key in snapshot.artifacts) {
      keys += key
    }
  }
  definition.requiredArtifactsByStep[resumeStepId].orEmpty().forEach { key ->
    if (key !in keys && resolvedArtifactValue(definition, snapshot, key).present) {
      keys += key
    }
  }
  return keys
}

internal fun resolvedArtifactValue(
  definition: WorkflowDefinition,
  snapshot: WorkflowSnapshotView,
  key: String,
): ResolvedRequiredArtifact =
  if (key in snapshot.artifacts) {
    ResolvedRequiredArtifact(present = true, value = snapshot.artifacts[key])
  } else {
    definition.requiredArtifactPresenceResolver.resolveRequiredArtifact(snapshot, key)
  }

internal fun compactContinueView(
  definition: WorkflowDefinition,
  snapshot: WorkflowSnapshotView,
  resume: WorkflowResumeView,
  continueStatus: String,
  workflowStatusBeforeContinue: String,
  continueStepLabel: String,
  continueStepDirective: String,
  continuationBrief: String,
  continuationEntryPrompt: String,
  declaredProjection: WorkflowInputProjection?,
): WorkflowCompactContinueView {
  val requiredKeys = resume.requiredArtifacts
  val availableKeys = resume.availableArtifacts
  val currentStepArtifactKeys = declaredProjection?.artifacts?.keys?.toList() ?: requiredKeys
  val currentStepArtifacts = declaredProjection?.artifacts?.map { (key, value) ->
    losslessProjectionArtifact(key, value)
  } ?: currentStepArtifactKeys.map { key ->
    val resolved = resolvedArtifactValue(definition, snapshot, key)
    artifactSummary(key, resolved.value, resolved.present)
  }
  val omittedKeys = availableKeys.filterNot(currentStepArtifactKeys::contains)
  return WorkflowCompactContinueView(
    workflowId = snapshot.workflowId,
    skillName = definition.skillName,
    continueStatus = continueStatus,
    workflowStatusBeforeContinue = workflowStatusBeforeContinue,
    startedAt = snapshot.startedAt,
    updatedAt = snapshot.updatedAt,
    resumeStepId = resume.resumeStepId,
    resumeStepLabel = continueStepLabel,
    continueStepDirective = continueStepDirective,
    referenceSections = definition.continuationReferenceSections[resume.resumeStepId].orEmpty(),
    requiredArtifactKeys = requiredKeys,
    availableArtifactKeys = availableKeys,
    missingArtifactKeys = resume.missingArtifacts,
    currentStepArtifacts = currentStepArtifacts,
    omittedArtifactKeys = omittedKeys,
    continuationBrief = continuationBrief,
    continuationEntryPrompt = continuationEntryPrompt,
    readOnlyFullStateGuidance =
    "Use workflow show for read-only full-state inspection, including the complete durable artifacts map.",
  )
}

internal fun losslessProjectionArtifact(key: String, value: Any?): WorkflowContinuationArtifactSummary {
  val sizeBytes = jsonString(value).toByteArray(Charsets.UTF_8).size
  return WorkflowContinuationArtifactSummary(
    key = key,
    present = true,
    inline = true,
    sizeBytes = sizeBytes,
    value = value,
    preview = null,
    truncated = false,
    omitted = false,
    omissionReason = null,
  )
}

internal fun artifactSummary(key: String, value: Any?, present: Boolean): WorkflowContinuationArtifactSummary {
  if (!present) {
    return WorkflowContinuationArtifactSummary(
      key = key,
      present = false,
      inline = false,
      sizeBytes = null,
      value = null,
      preview = null,
      truncated = false,
      omitted = true,
      omissionReason = "missing_required_artifact",
    )
  }
  val serialized = jsonString(value)
  val sizeBytes = serialized.toByteArray(Charsets.UTF_8).size
  val inline = sizeBytes <= COMPACT_ARTIFACT_INLINE_MAX_BYTES
  return WorkflowContinuationArtifactSummary(
    key = key,
    present = true,
    inline = inline,
    sizeBytes = sizeBytes,
    value = if (inline) value else null,
    preview = if (inline) null else serialized.take(COMPACT_ARTIFACT_PREVIEW_CHARS),
    truncated = !inline && serialized.length > COMPACT_ARTIFACT_PREVIEW_CHARS,
    omitted = !inline,
    omissionReason = if (inline) null else "artifact_exceeds_inline_limit",
  )
}

internal fun continuationBrief(
  definition: WorkflowDefinition,
  workflowId: String,
  resumeStepId: String,
  continueStatus: String,
  nextAction: String,
  currentStepArtifactKeys: List<String>,
  omittedArtifactKeys: List<String>,
): String {
  val stepLabel = definition.stepLabels[resumeStepId] ?: resumeStepId
  val currentArtifacts = currentStepArtifactKeys.joinToString().ifBlank { "none" }
  val omittedArtifacts = omittedArtifactKeys.joinToString().ifBlank { "none" }
  val instructionPath = CONTINUATION_CONTENT_PATHS[definition.skillName]
    ?.let { path -> "Follow the normal step instructions in `$path`. " }
    .orEmpty()
  return "Resume `${definition.skillName}` workflow `$workflowId` from `$stepLabel` (`$resumeStepId`). " +
    instructionPath +
    "Use `current_step_artifacts` in this compact payload ($currentArtifacts) as authoritative " +
    "current-step context instead of reconstructing prior context from chat history. " +
    "Omitted artifact keys ($omittedArtifacts) remain private phase context. Explicit operator diagnostics " +
    "may inspect them with `workflow show`; phase agents must not. Workflow activation status: " +
    "`$continueStatus`. Next action: $nextAction"
}

internal fun continuationEntryPrompt(
  definition: WorkflowDefinition,
  workflowId: String,
  sessionId: String,
  resumeStepId: String,
  continueStatus: String,
  currentStepArtifactKeys: List<String>,
  omittedArtifactKeys: List<String>,
  nextAction: String,
  sessionSummary: Map<String, Any?>,
  extraFields: Map<String, Any?>,
  nextAttemptCount: Int,
): String {
  val references = definition.continuationReferenceSections[resumeStepId].orEmpty().joinToString("; ")
  val directive =
    definition.continuationDirectives[resumeStepId]
      ?: (
        "Resume the workflow from the recovered current step using the persisted artifacts as " +
          "authoritative context."
        )
  val currentArtifacts = currentStepArtifactKeys.joinToString().ifBlank { "none" }
  val omittedArtifacts = omittedArtifactKeys.joinToString().ifBlank { "none" }
  val commonLines =
    mutableListOf(
      "Use `${definition.skillName}` in continuation mode.",
      "Workflow id: $workflowId",
      "Session id: ${sessionId.ifBlank { "(none)" }}",
      "Continue status: $continueStatus",
      "Resume step: $resumeStepId (${definition.stepLabels[resumeStepId] ?: resumeStepId})",
    )
  if (definition.workflowName == FeatureTaskRuntimePhaseWorkflowDefinition.definition.workflowName) {
    commonLines += "Feature: ${(extraFields["feature_name"] as String).ifBlank { "(unknown)" }}"
    commonLines += "Feature size: ${(extraFields["feature_size"] as String).ifBlank { "(unknown)" }}"
    commonLines += "Branch: ${(extraFields["branch_name"] as String).ifBlank { "(unknown)" }}"
  }
  commonLines += "Current-step artifacts: $currentArtifacts"
  commonLines += "Omitted artifact keys: $omittedArtifacts"
  if (definition.skillName == "bill-feature-verify") {
    commonLines += "Acceptance criteria count: ${sessionSummary["acceptance_criteria_count"] ?: 0}"
    commonLines += "Rollout relevant: ${sessionSummary["rollout_relevant"] ?: false}"
  }
  val specSummary = sessionSummary["spec_summary"]?.toString()?.ifBlank { "(none saved)" } ?: "(none saved)"
  commonLines += "Spec summary: $specSummary"
  commonLines += "Reference sections: ${references.ifBlank { "normal step instructions only" }}"
  commonLines +=
    "Rules: do not rerun completed steps unless the workflow sends work backwards; treat " +
    "`current_step_artifacts` as the complete authoritative phase input. Omitted keys remain private; " +
    "`workflow show` is an explicit operator diagnostic and must not widen phase context."
  commonLines +=
    "Workflow update rule: every step_updates item must include step_id, status, and integer " +
    "attempt_count; use attempt_count $nextAttemptCount for `$resumeStepId` unless a later retry increments it."
  commonLines += "Keep the same workflow_id and session_id, then continue `${definition.skillName}`."
  commonLines += "Step directive: $directive"
  commonLines += "Immediate next action: $nextAction"
  return commonLines.joinToString("\n")
}

internal fun implementExtraFields(artifacts: Map<String, Any?>): Map<String, Any?> {
  val assessment = artifacts["assessment"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
  val branch = artifacts["branch"]
  val branchName =
    when (branch) {
      is Map<*, *> -> branch["branch_name"]?.toString().orEmpty().trim()
      is String -> branch.trim()
      else -> ""
    }
  return linkedMapOf(
    "feature_name" to assessment["feature_name"].toStringOrEmpty(),
    "feature_size" to assessment["feature_size"].toStringOrEmpty(),
    "branch_name" to branchName,
  )
}

internal const val COMPACT_ARTIFACT_INLINE_MAX_BYTES = 4096
internal const val COMPACT_ARTIFACT_PREVIEW_CHARS = 1024

internal val CONTINUATION_CONTENT_PATHS: Map<String, String> = mapOf(
  "bill-feature-verify" to "skills/bill-feature-verify/content.md",
)

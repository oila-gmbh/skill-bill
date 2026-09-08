package skillbill.workflow.engine

import skillbill.workflow.engine.model.WorkflowCompactContinueView
import skillbill.workflow.engine.model.WorkflowContinuationArtifactSummary
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.engine.model.WorkflowInputProjection
import skillbill.workflow.engine.model.WorkflowResumeView
import skillbill.workflow.engine.model.WorkflowSnapshotView

internal data class ContinueStepPresentation(
  val continueStatus: String,
  val workflowStatusBeforeContinue: String,
  val continueStepLabel: String,
  val continueStepDirective: String,
)

internal data class ContinuePromptTexts(
  val continuationBrief: String,
  val continuationEntryPrompt: String,
)

internal data class CompactContinueViewRequest(
  val definition: WorkflowDefinition,
  val snapshot: WorkflowSnapshotView,
  val resume: WorkflowResumeView,
  val presentation: ContinueStepPresentation,
  val texts: ContinuePromptTexts,
  val declaredProjection: WorkflowInputProjection?,
)

internal fun compactContinueView(request: CompactContinueViewRequest): WorkflowCompactContinueView {
  val requiredKeys = request.resume.requiredArtifacts
  val availableKeys = request.resume.availableArtifacts
  val currentStepArtifactKeys = request.declaredProjection?.artifacts?.keys?.toList() ?: requiredKeys
  val currentStepArtifacts = request.declaredProjection?.artifacts?.map { (key, value) ->
    losslessProjectionArtifact(key, value)
  } ?: currentStepArtifactKeys.map { key ->
    val resolved = resolvedArtifactValue(request.definition, request.snapshot, key)
    artifactSummary(key, resolved.value, resolved.present)
  }
  val omittedKeys = availableKeys.filterNot(currentStepArtifactKeys::contains)
  return WorkflowCompactContinueView(
    workflowId = request.snapshot.workflowId,
    skillName = request.definition.skillName,
    continueStatus = request.presentation.continueStatus,
    workflowStatusBeforeContinue = request.presentation.workflowStatusBeforeContinue,
    startedAt = request.snapshot.startedAt,
    updatedAt = request.snapshot.updatedAt,
    resumeStepId = request.resume.resumeStepId,
    resumeStepLabel = request.presentation.continueStepLabel,
    continueStepDirective = request.presentation.continueStepDirective,
    referenceSections = request.definition.continuationReferenceSections[request.resume.resumeStepId].orEmpty(),
    requiredArtifactKeys = requiredKeys,
    availableArtifactKeys = availableKeys,
    missingArtifactKeys = request.resume.missingArtifacts,
    currentStepArtifacts = currentStepArtifacts,
    omittedArtifactKeys = omittedKeys,
    continuationBrief = request.texts.continuationBrief,
    continuationEntryPrompt = request.texts.continuationEntryPrompt,
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

internal const val COMPACT_ARTIFACT_INLINE_MAX_BYTES = 4096
internal const val COMPACT_ARTIFACT_PREVIEW_CHARS = 1024

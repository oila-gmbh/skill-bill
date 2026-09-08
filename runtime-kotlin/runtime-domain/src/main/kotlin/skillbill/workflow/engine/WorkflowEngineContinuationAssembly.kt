package skillbill.workflow.engine

import skillbill.workflow.engine.model.ResolvedRequiredArtifact
import skillbill.workflow.engine.model.WorkflowContinueDecision
import skillbill.workflow.engine.model.WorkflowContinueView
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.engine.model.WorkflowInputProjection
import skillbill.workflow.engine.model.WorkflowResumeView
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowStateSnapshot
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
): ResolvedRequiredArtifact = if (key in snapshot.artifacts) {
  ResolvedRequiredArtifact(present = true, value = snapshot.artifacts[key])
} else {
  definition.requiredArtifactPresenceResolver.resolveRequiredArtifact(snapshot, key)
}

internal data class AssembledContinueTexts(
  val stepArtifactKeys: List<String>,
  val stepArtifacts: Map<String, Any?>,
  val extraFields: Map<String, Any?>,
  val continuationBrief: String,
  val continuationEntryPrompt: String,
)

internal data class ContinueAssemblyContext(
  val definition: WorkflowDefinition,
  val record: WorkflowStateSnapshot,
  val resume: WorkflowResumeView,
  val snapshot: WorkflowSnapshotView,
  val declaredProjection: WorkflowInputProjection?,
)

internal data class AssembleContinueTextsRequest(
  val context: ContinueAssemblyContext,
  val continueStatus: String,
  val sessionSummary: Map<String, Any?>,
  val nextAttemptCount: Int,
)

internal data class BuildContinueDecisionRequest(
  val context: ContinueAssemblyContext,
  val continueStatus: String,
  val workflowStatusBeforeContinue: String,
  val actualContinueStatus: String,
  val nextAttemptCount: Int,
  val sessionSummary: Map<String, Any?>,
)

internal fun assembleContinueTexts(request: AssembleContinueTextsRequest): AssembledContinueTexts {
  val context = request.context
  val definition = context.definition
  val resume = context.resume
  val snapshot = context.snapshot
  val declaredProjection = context.declaredProjection
  val stepArtifactKeys = declaredProjection?.artifacts?.keys?.toList()
    ?: continueArtifactKeys(definition, resume.resumeStepId, snapshot)
  val stepArtifacts = declaredProjection?.artifacts ?: stepArtifactKeys.associateWith { key ->
    resolvedArtifactValue(definition, snapshot, key).value
  }
  val artifactKeys = ContinuationArtifactKeys(
    currentStepArtifactKeys = resume.requiredArtifacts,
    omittedArtifactKeys = resume.availableArtifacts.filterNot(resume.requiredArtifacts::contains),
  )
  val extraFields =
    if (definition.workflowName == FeatureTaskRuntimePhaseWorkflowDefinition.definition.workflowName) {
      implementExtraFields(snapshot.artifacts)
    } else {
      emptyMap()
    }
  return AssembledContinueTexts(
    stepArtifactKeys = stepArtifactKeys,
    stepArtifacts = stepArtifacts,
    extraFields = extraFields,
    continuationBrief = continuationBrief(
      ContinuationBriefRequest(
        definition = definition,
        workflowId = context.record.workflowId,
        resumeStepId = resume.resumeStepId,
        continueStatus = request.continueStatus,
        nextAction = resume.nextAction,
        artifactKeys = artifactKeys,
      ),
    ),
    continuationEntryPrompt = continuationEntryPrompt(
      ContinuationEntryPromptRequest(
        definition = definition,
        identity = ContinuationIdentity(
          workflowId = context.record.workflowId,
          sessionId = context.record.sessionId.orEmpty(),
          resumeStepId = resume.resumeStepId,
          continueStatus = request.continueStatus,
          nextAction = resume.nextAction,
          nextAttemptCount = request.nextAttemptCount,
        ),
        artifactKeys = artifactKeys,
        sessionSummary = request.sessionSummary,
        extraFields = extraFields,
      ),
    ),
  )
}

internal fun buildContinueDecision(request: BuildContinueDecisionRequest): WorkflowContinueDecision {
  val context = request.context
  val definition = context.definition
  val resume = context.resume
  val stepLabel = definition.stepLabels[resume.resumeStepId] ?: resume.resumeStepId
  val stepDirective = definition.continuationDirectives[resume.resumeStepId]
    ?: "Resume the workflow from the current step using the recovered artifacts as authoritative context."
  val presentation = ContinueStepPresentation(
    continueStatus = request.continueStatus,
    workflowStatusBeforeContinue = request.workflowStatusBeforeContinue,
    continueStepLabel = stepLabel,
    continueStepDirective = stepDirective,
  )
  val assembled = assembleContinueTexts(
    AssembleContinueTextsRequest(
      context = context,
      continueStatus = request.continueStatus,
      sessionSummary = request.sessionSummary,
      nextAttemptCount = request.nextAttemptCount,
    ),
  )
  val compact = compactContinueView(
    CompactContinueViewRequest(
      definition = definition,
      snapshot = context.snapshot,
      resume = resume,
      presentation = presentation,
      texts = ContinuePromptTexts(assembled.continuationBrief, assembled.continuationEntryPrompt),
      declaredProjection = context.declaredProjection,
    ),
  )
  return WorkflowContinueDecision(
    view = WorkflowContinueView(
      resume = resume,
      skillName = definition.skillName,
      workflowStatusBeforeContinue = request.workflowStatusBeforeContinue,
      continueStatus = request.continueStatus,
      continueStepId = resume.resumeStepId,
      continueStepLabel = stepLabel,
      continueStepDirective = stepDirective,
      referenceSections = definition.continuationReferenceSections[resume.resumeStepId].orEmpty(),
      stepArtifactKeys = assembled.stepArtifactKeys,
      stepArtifacts = assembled.stepArtifacts,
      extraFields = assembled.extraFields,
      sessionSummary = request.sessionSummary,
      continuationBrief = assembled.continuationBrief,
      continuationEntryPrompt = assembled.continuationEntryPrompt,
      compact = compact,
    ),
    shouldReopen = request.actualContinueStatus == "reopened",
    resumeStepId = resume.resumeStepId,
    nextAttemptCount = request.nextAttemptCount,
  )
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

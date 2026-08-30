package skillbill.application.goalrunner

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.featuretask.buildCompletedUpstreamMissingOutputRepair
import skillbill.application.featuretask.diagnoseUnsettledCompletedUpstreamPhaseId
import skillbill.application.featuretask.featureSizeFromArtifacts
import skillbill.application.featuretask.model.CompletedUpstreamRepairRequest
import skillbill.application.featuretask.phaseLedgerFrom
import skillbill.application.featuretask.phaseRecordsFrom
import skillbill.application.goalrunner.model.GoalRunnerAppliedRepair
import skillbill.application.goalrunner.model.GoalRunnerWedgeClass
import skillbill.application.workflow.WorkflowFamily
import skillbill.application.workflow.updateGoalParentForBlockedPhaseRetry
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputFailureReason
import skillbill.ports.workflow.gitops.recoverGoalSubtaskReviewBaseline
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.goal.model.GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import java.time.Instant

internal data class UnreachableReviewRepairContext(
  val review: GoalSubtaskReviewState,
  val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  val failedSha: String,
  val replacement: String,
  val baselineUntrackedPaths: List<String>,
)

internal fun unreachableReviewFailedSha(wedgeClass: GoalRunnerWedgeClass, review: GoalSubtaskReviewState): String? =
  when (wedgeClass) {
    GoalRunnerWedgeClass.UNREACHABLE_REVIEW_BASE -> review.reviewBaseSha
    GoalRunnerWedgeClass.UNREACHABLE_REMEDIATION_BASE -> review.remediationBaseSha
    else -> null
  }

internal data class UnreachableReviewRepairLookup(
  val wedgeClass: GoalRunnerWedgeClass,
  val wedgeDiagnosis: GoalRunnerChildRepairWedgeDiagnosis,
  val repoRoot: java.nio.file.Path,
  val gitOperations: WorkflowGitOperations,
  val review: GoalSubtaskReviewState?,
  val continuation: FeatureTaskRuntimeGoalContinuationArtifact?,
)

internal fun unreachableReviewRepairContext(lookup: UnreachableReviewRepairLookup): UnreachableReviewRepairContext? {
  val wedgeClass = lookup.wedgeClass
  val review = lookup.review
  val continuation = lookup.continuation
  val failedSha = review?.let { unreachableReviewFailedSha(wedgeClass, it) }
  if (review == null || continuation == null || failedSha == null) return null
  if (!lookup.wedgeDiagnosis.isUnreachable(lookup.repoRoot, failedSha)) return null
  val recovered = lookup.gitOperations.recoverGoalSubtaskReviewBaseline(
    lookup.repoRoot,
    GoalSubtaskReviewBaselineRecoveryRequest(
      unreachableSha = failedSha,
      failureReason = GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR,
      baselineUntrackedPaths = review.baselineUntrackedPaths,
    ),
    continuation.goalBranch,
  )
  val recoveredBaseline = recovered.baseline
  if (!recovered.ok || recoveredBaseline == null) return null
  return UnreachableReviewRepairContext(
    review = review,
    continuation = continuation,
    failedSha = failedSha,
    replacement = recoveredBaseline.reviewBaseSha,
    baselineUntrackedPaths = recoveredBaseline.baselineUntrackedPaths,
  )
}

internal fun healedUnreachableReviewState(
  wedgeClass: GoalRunnerWedgeClass,
  context: UnreachableReviewRepairContext,
): GoalSubtaskReviewState? = when (wedgeClass) {
  GoalRunnerWedgeClass.UNREACHABLE_REVIEW_BASE -> context.review.copy(
    reviewBaseSha = context.replacement,
    baselineUntrackedPaths = context.baselineUntrackedPaths,
  )
  GoalRunnerWedgeClass.UNREACHABLE_REMEDIATION_BASE -> context.review.copy(remediationBaseSha = context.replacement)
  else -> null
}

internal fun applyUnreachableReviewRepairToState(
  wedgeClass: GoalRunnerWedgeClass,
  state: GoalRunnerChildRepairWedgeApplyLoop.ApplyState,
  context: UnreachableReviewRepairContext,
) {
  val healed = healedUnreachableReviewState(wedgeClass, context) ?: return
  state.workingReview = healed
  state.patch[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY] = healed.toArtifactMap()
  val recoveryEvidence = linkedMapOf<String, Any?>(
    "original_sha" to context.failedSha,
    "replacement_sha" to context.replacement,
    "repointed_field" to wedgeClass.durableField,
    "failure_reason" to "base_not_ancestor",
    "failure_message" to "Operator goal repair repointed unreachable ${wedgeClass.durableField}.",
    "goal_branch" to context.continuation.goalBranch,
  )
  val priorRecoveries = (state.artifacts[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY] as? List<*>).orEmpty()
  val existingRecoveries = (state.patch[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY] as? List<*>) ?: priorRecoveries
  state.patch[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY] = existingRecoveries + recoveryEvidence
  recordChildRepairWedge(state, wedgeClass, priorValue = context.failedSha, newValue = context.replacement)
}

internal fun applyStaleBlockedChildRepairWedge(
  wedgeClass: GoalRunnerWedgeClass,
  state: GoalRunnerChildRepairWedgeApplyLoop.ApplyState,
) {
  val continuation = state.workingContinuation ?: return
  val identity = GoalContinuation(
    issueKey = continuation.issueKey,
    subtaskId = continuation.subtaskId,
    suppressPr = continuation.suppressPr,
    goalBranch = continuation.goalBranch,
  )
  val stored = goalContinuationOutcome(
    state.artifacts,
    state.request.issueKey,
    state.request.subtaskId,
    continuation.suppressPr,
  )?.takeIf { it.status == GoalRunnerTerminalStatus.BLOCKED } ?: return
  val derived = derivedTerminalOutcomeFor(state.record, state.artifacts, identity) { null }
  if (
    nonCompleteStoredOutcomeIsCorroborated(
      stored.copy(workflowId = state.request.workflowId),
      derived,
      state.record,
    )
  ) {
    return
  }
  state.patch["goal_continuation_outcome"] = null
  recordChildRepairWedge(state, wedgeClass, priorValue = stored.blockedReason, newValue = null)
}

internal fun applyCompletedUpstreamChildRepairWedge(
  wedgeClass: GoalRunnerWedgeClass,
  state: GoalRunnerChildRepairWedgeApplyLoop.ApplyState,
  workflowStates: WorkflowStateRepository,
  engine: WorkflowEngine,
  decompositionManifestValidator: DecompositionManifestValidator?,
) {
  val phaseRecords = phaseRecordsFrom(state.artifacts)
  val featureSize = featureSizeFromArtifacts(state.artifacts)
  val qualityGateSelection = state.workingContinuation?.qualityGateSelection
    ?: FeatureTaskRuntimeQualityGateSelection.VALIDATE
  val resumePhaseId = diagnoseUnsettledCompletedUpstreamPhaseId(
    phaseRecords,
    featureSize,
    qualityGateSelection,
  ) ?: return
  val input = buildCompletedUpstreamMissingOutputRepair(
    CompletedUpstreamRepairRequest(
      phaseRecords = phaseRecords,
      ledger = phaseLedgerFrom(state.artifacts),
      featureSize = featureSize,
      resumePhaseId = resumePhaseId,
      reason = "Operator goal repair reopened '$resumePhaseId' because a completed upstream phase " +
        "record had no settled output for a blocked consumer.",
      qualityGateSelection = qualityGateSelection,
    ),
  )
  val updated = engine.updateRecord(WorkflowFamily.TASK_RUNTIME.definition, state.record, input)
  WorkflowFamily.TASK_RUNTIME.save(workflowStates, updated)
  state.record = updated
  state.artifacts = decodeArtifacts(updated.artifactsJson)
  state.workingContinuation = continuationArtifactFromMap(state.artifacts)
  state.workingReview = GoalSubtaskReviewArtifactDecoder.decode(state.artifacts)?.state
  state.manifestProjectionArtifactsJson = decompositionManifestValidator?.let { validator ->
    engine.updateGoalParentForBlockedPhaseRetry(
      unitOfWork = state.request.unitOfWork,
      childWorkflowId = state.request.workflowId,
      childArtifacts = state.artifacts,
      phaseId = resumePhaseId,
      validator = validator,
    )
  }
  val repair = GoalRunnerAppliedRepair(
    subtaskId = state.request.subtaskId,
    workflowId = state.request.workflowId,
    wedgeClass = wedgeClass,
    field = resumePhaseId,
    priorValue = "completed_without_output",
    newValue = "pending",
  )
  state.applied += repair
  state.evidenceEntries += childRepairWedgeEvidenceMap(repair)
}

internal fun recordChildRepairWedge(
  state: GoalRunnerChildRepairWedgeApplyLoop.ApplyState,
  wedgeClass: GoalRunnerWedgeClass,
  priorValue: String?,
  newValue: String?,
) {
  val repair = GoalRunnerAppliedRepair(
    subtaskId = state.request.subtaskId,
    workflowId = state.request.workflowId,
    wedgeClass = wedgeClass,
    field = wedgeClass.durableField,
    priorValue = priorValue,
    newValue = newValue,
  )
  state.applied += repair
  state.evidenceEntries += childRepairWedgeEvidenceMap(repair)
}

internal fun childRepairWedgeEvidenceMap(repair: GoalRunnerAppliedRepair): Map<String, Any?> = linkedMapOf(
  "wedge_class" to repair.wedgeClass.wireValue,
  "field" to repair.field,
  "prior_value" to repair.priorValue,
  "new_value" to repair.newValue,
  "subtask_id" to repair.subtaskId,
  "workflow_id" to repair.workflowId,
  "repaired_at" to Instant.now().toString(),
)

internal fun continuationArtifactFromMap(artifacts: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationArtifact? {
  val raw = artifacts[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY] as? Map<*, *> ?: return null
  @Suppress("UNCHECKED_CAST")
  return FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(raw as Map<String, Any?>)
}

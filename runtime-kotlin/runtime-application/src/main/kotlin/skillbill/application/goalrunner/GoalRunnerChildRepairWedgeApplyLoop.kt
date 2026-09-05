package skillbill.application.goalrunner

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.featuretask.buildCompletedUpstreamMissingOutputRepair
import skillbill.application.featuretask.diagnoseUnsettledCompletedUpstreamPhaseId
import skillbill.application.featuretask.featureSizeFromArtifacts
import skillbill.application.featuretask.model.CompletedUpstreamRepairRequest
import skillbill.application.goalrunner.model.GoalContinuation
import skillbill.application.goalrunner.model.GoalRunnerAppliedRepair
import skillbill.application.goalrunner.model.GoalRunnerChildRepairApplyRequest
import skillbill.application.goalrunner.model.GoalRunnerChildRepairApplyResult
import skillbill.application.goalrunner.model.GoalRunnerWedgeClass
import skillbill.application.goalrunner.model.PortableReviewBaselineValidation
import skillbill.application.goalrunner.model.PortableReviewBaselineValidationRequest
import skillbill.application.phaseartifacts.phaseLedgerFrom
import skillbill.application.phaseartifacts.phaseRecordsFrom
import skillbill.application.workflow.model.WorkflowFamily
import skillbill.application.workflow.updateGoalParentForBlockedPhaseRetry
import skillbill.contracts.JsonSupport
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.goalrunner.persistence.PortableReviewBaselinePersistence
import skillbill.ports.goalrunner.persistence.model.PortableReviewBaselineRepairContext
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputFailureReason
import skillbill.ports.workflow.gitops.recoverGoalSubtaskReviewBaseline
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.goal.model.GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.goal.model.PortableReviewBaselineBlockedReason
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import java.nio.file.Path
import java.time.Clock

class GoalRunnerChildRepairWedgeApplyLoop(
  private val engine: WorkflowEngine,
  private val gitOperations: WorkflowGitOperations,
  private val wedgeDiagnosis: GoalRunnerChildRepairWedgeDiagnosis,
  private val decompositionManifestValidator: DecompositionManifestValidator,
  private val portableReviewBaselinePersistence: PortableReviewBaselinePersistence,
  private val clock: Clock,
) {
  fun apply(request: GoalRunnerChildRepairApplyRequest): GoalRunnerChildRepairApplyResult {
    if (request.wedgeClasses.isEmpty()) return GoalRunnerChildRepairApplyResult()
    val workflowStates = request.unitOfWork.workflowStates
    var record = WorkflowFamily.TASK_RUNTIME.get(workflowStates, request.workflowId)
      ?: return GoalRunnerChildRepairApplyResult()
    var artifacts = decodeArtifacts(record.artifactsJson)
    val state = ApplyState(
      request = request,
      record = record,
      artifacts = artifacts,
      workingContinuation = continuationArtifactFromMap(artifacts),
      workingReview = GoalSubtaskReviewArtifactDecoder.decode(artifacts)?.state,
      clock = clock,
    )
    for (wedgeClass in request.wedgeClasses.distinct()) {
      applyWedgeClass(wedgeClass, state, workflowStates)
      record = state.record
      artifacts = state.artifacts
    }
    if (state.applied.isEmpty()) return GoalRunnerChildRepairApplyResult()
    val priorEvidence = (artifacts[GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY] as? List<*>).orEmpty()
    state.patch[GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY] = priorEvidence + state.evidenceEntries
    val updated = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = record.workflowStatus,
        currentStepId = record.currentStepId,
        stepUpdates = null,
        artifactsPatch = state.patch,
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    WorkflowFamily.TASK_RUNTIME.save(workflowStates, updated)
    return GoalRunnerChildRepairApplyResult(
      repairs = state.applied,
      manifestProjectionArtifactsJson = state.manifestProjectionArtifactsJson,
    )
  }

  private fun applyWedgeClass(
    wedgeClass: GoalRunnerWedgeClass,
    state: ApplyState,
    workflowStates: WorkflowStateRepository,
  ) {
    when (wedgeClass) {
      GoalRunnerWedgeClass.PHASE_OUTPUT_CONTRACT_INCOMPATIBLE -> Unit
      GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH -> applyMissingValidationDepth(wedgeClass, state)
      GoalRunnerWedgeClass.MISSING_QUALITY_GATE_SELECTION -> applyMissingQualityGateSelection(wedgeClass, state)
      GoalRunnerWedgeClass.UNREACHABLE_REVIEW_BASE,
      GoalRunnerWedgeClass.UNREACHABLE_REMEDIATION_BASE,
      -> applyUnreachableReviewBase(wedgeClass, state)
      GoalRunnerWedgeClass.STALE_BLOCKED_CONTINUATION_OUTCOME ->
        applyStaleBlockedChildRepairWedge(wedgeClass, state)
      GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT ->
        applyCompletedUpstreamChildRepairWedge(
          wedgeClass = wedgeClass,
          state = state,
          workflowStates = workflowStates,
          engine = engine,
          decompositionManifestValidator = decompositionManifestValidator,
        )
      GoalRunnerWedgeClass.INVALID_PORTABLE_REVIEW_BASELINE -> Unit
    }
  }

  private fun applyMissingValidationDepth(wedgeClass: GoalRunnerWedgeClass, state: ApplyState) {
    val continuation = state.workingContinuation ?: return
    if (continuation.validationDepth != null) return
    val depth = ValidationDepth.FULL
    val healed = continuation.copy(validationDepth = depth)
    state.workingContinuation = healed
    state.patch[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY] = healed.toArtifactMap()
    recordChildRepairWedge(state, wedgeClass, priorValue = null, newValue = depth.wireValue)
  }

  private fun applyMissingQualityGateSelection(wedgeClass: GoalRunnerWedgeClass, state: ApplyState) {
    val continuation = state.workingContinuation ?: return
    if (continuation.qualityGateSelection != null) return
    val selection = FeatureTaskRuntimeQualityGateSelection.VALIDATE
    val healed = continuation.copy(qualityGateSelection = selection)
    state.workingContinuation = healed
    state.patch[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY] = healed.toArtifactMap()
    recordChildRepairWedge(state, wedgeClass, priorValue = null, newValue = selection.wireValue)
  }

  private fun applyUnreachableReviewBase(wedgeClass: GoalRunnerWedgeClass, state: ApplyState) {
    val lookup = UnreachableReviewRepairLookup(
      wedgeClass = wedgeClass,
      wedgeDiagnosis = wedgeDiagnosis,
      repoRoot = state.request.repoRoot,
      gitOperations = gitOperations,
      review = state.workingReview,
      continuation = state.workingContinuation,
      portableContext = state.request.portableContext,
      portableReviewBaselinePersistence = portableReviewBaselinePersistence,
    )
    val context = unreachableReviewRepairContext(lookup) ?: return
    applyUnreachableReviewRepairToState(
      wedgeClass = wedgeClass,
      state = state,
      context = context,
      applyContext = UnreachableReviewRepairApplyContext(
        engine = engine,
        portableReviewBaselinePersistence = portableReviewBaselinePersistence,
        workflowStates = state.request.unitOfWork.workflowStates,
        portableContext = state.request.portableContext,
        repoRoot = state.request.repoRoot,
        workflowId = state.request.workflowId,
      ),
    )
  }

  class ApplyState(
    val request: GoalRunnerChildRepairApplyRequest,
    record: WorkflowStateSnapshot,
    artifacts: Map<String, Any?>,
    workingContinuation: FeatureTaskRuntimeGoalContinuationArtifact?,
    workingReview: GoalSubtaskReviewState?,
    val clock: Clock,
  ) {
    var record: WorkflowStateSnapshot = record
    var artifacts: Map<String, Any?> = artifacts
    val patch: LinkedHashMap<String, Any?> = linkedMapOf()
    val applied: MutableList<GoalRunnerAppliedRepair> = mutableListOf()
    val evidenceEntries: MutableList<Map<String, Any?>> = mutableListOf()
    var workingContinuation: FeatureTaskRuntimeGoalContinuationArtifact? =
      workingContinuation
    var workingReview: GoalSubtaskReviewState? = workingReview
    var manifestProjectionArtifactsJson: String? = null
  }
}

internal data class UnreachableReviewRepairContext(
  val review: GoalSubtaskReviewState,
  val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  val failedSha: String,
  val replacement: String,
  val baselineUntrackedPaths: List<String>,
  val recoveredBaseline: GoalSubtaskReviewBaseline,
)

fun unreachableReviewFailedSha(wedgeClass: GoalRunnerWedgeClass, review: GoalSubtaskReviewState): String? =
  when (wedgeClass) {
    GoalRunnerWedgeClass.UNREACHABLE_REVIEW_BASE -> review.reviewBaseSha
    GoalRunnerWedgeClass.UNREACHABLE_REMEDIATION_BASE -> review.remediationBaseSha
    GoalRunnerWedgeClass.PHASE_OUTPUT_CONTRACT_INCOMPATIBLE,
    GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH,
    GoalRunnerWedgeClass.MISSING_QUALITY_GATE_SELECTION,
    GoalRunnerWedgeClass.STALE_BLOCKED_CONTINUATION_OUTCOME,
    GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT,
    GoalRunnerWedgeClass.INVALID_PORTABLE_REVIEW_BASELINE,
    -> null
  }

internal data class UnreachableReviewRepairLookup(
  val wedgeClass: GoalRunnerWedgeClass,
  val wedgeDiagnosis: GoalRunnerChildRepairWedgeDiagnosis,
  val repoRoot: Path,
  val gitOperations: WorkflowGitOperations,
  val review: GoalSubtaskReviewState?,
  val continuation: FeatureTaskRuntimeGoalContinuationArtifact?,
  val portableContext: PortableReviewBaselineRepairContext?,
  val portableReviewBaselinePersistence: PortableReviewBaselinePersistence,
)

internal data class UnreachableReviewRepairApplyContext(
  val engine: WorkflowEngine,
  val portableReviewBaselinePersistence: PortableReviewBaselinePersistence,
  val workflowStates: WorkflowStateRepository,
  val portableContext: PortableReviewBaselineRepairContext?,
  val repoRoot: Path,
  val workflowId: String,
)

internal fun unreachableReviewRepairContext(lookup: UnreachableReviewRepairLookup): UnreachableReviewRepairContext? {
  val continuation = lookup.continuation ?: return null
  val review = lookup.review ?: portableReviewStateFromArtifact(lookup)
  val failedSha = review?.let { unreachableReviewFailedSha(lookup.wedgeClass, it) }
  if (review == null || failedSha == null || !lookup.wedgeDiagnosis.isUnreachable(lookup.repoRoot, failedSha)) {
    return null
  }
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
    recoveredBaseline = recoveredBaseline,
  )
}

private fun portableReviewStateFromArtifact(lookup: UnreachableReviewRepairLookup): GoalSubtaskReviewState? {
  val portableContext = lookup.portableContext ?: return null
  val continuation = lookup.continuation ?: return null
  val validation = PortableReviewBaselineValidator.validateArtifactIntegrity(
    PortableReviewBaselineValidationRequest(
      persistence = lookup.portableReviewBaselinePersistence,
      repoRoot = lookup.repoRoot,
      manifest = portableContext.manifest,
      subtaskId = portableContext.subtaskId,
      expectedWorkflowId = portableContext.workflowId,
      expectedRepositoryIdentity = portableContext.repositoryIdentity,
      expectedBranch = continuation.goalBranch,
      gitOperations = lookup.gitOperations,
    ),
  )
  val artifact = when (validation) {
    is PortableReviewBaselineValidation.Valid -> validation.artifact
    is PortableReviewBaselineValidation.Blocked ->
      validation.artifact?.takeIf { validation.reason == PortableReviewBaselineBlockedReason.UNREACHABLE_BASE }
  } ?: return null
  return GoalSubtaskReviewState.initial(
    reviewBaseSha = artifact.reviewBaseSha,
    baselineUntrackedPaths = artifact.baselineUntrackedPaths,
    codeReviewMode = continuation.codeReviewMode,
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
  GoalRunnerWedgeClass.PHASE_OUTPUT_CONTRACT_INCOMPATIBLE,
  GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH,
  GoalRunnerWedgeClass.MISSING_QUALITY_GATE_SELECTION,
  GoalRunnerWedgeClass.STALE_BLOCKED_CONTINUATION_OUTCOME,
  GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT,
  GoalRunnerWedgeClass.INVALID_PORTABLE_REVIEW_BASELINE,
  -> null
}

internal fun applyUnreachableReviewRepairToState(
  wedgeClass: GoalRunnerWedgeClass,
  state: GoalRunnerChildRepairWedgeApplyLoop.ApplyState,
  context: UnreachableReviewRepairContext,
  applyContext: UnreachableReviewRepairApplyContext,
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
  val portableContext = applyContext.portableContext
  if (
    portableContext != null &&
    PortableReviewBaselineRecovery.artifactExists(
      applyContext.portableReviewBaselinePersistence,
      applyContext.repoRoot,
      portableContext.manifest,
      portableContext.subtaskId,
    )
  ) {
    val auditEntry = PortableReviewBaselineRecovery.recordUnreachableBaseRecovery(
      applyContext.portableReviewBaselinePersistence,
      applyContext.repoRoot,
      portableContext,
      context.recoveredBaseline,
      context.continuation.goalBranch,
    )
    context.continuation.parentWorkflowId?.takeIf(String::isNotBlank)?.let { parentWorkflowId ->
      PortableReviewBaselineRecovery.appendParentRecoveryAudit(
        applyContext.engine,
        applyContext.workflowStates,
        parentWorkflowId,
        auditEntry,
      )
    }
  }
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
  decompositionManifestValidator: DecompositionManifestValidator,
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
  state.manifestProjectionArtifactsJson = engine.updateGoalParentForBlockedPhaseRetry(
    unitOfWork = state.request.unitOfWork,
    childWorkflowId = state.request.workflowId,
    childArtifacts = state.artifacts,
    phaseId = resumePhaseId,
    validator = decompositionManifestValidator,
  )
  val repair = GoalRunnerAppliedRepair(
    subtaskId = state.request.subtaskId,
    workflowId = state.request.workflowId,
    wedgeClass = wedgeClass,
    field = resumePhaseId,
    priorValue = "completed_without_output",
    newValue = "pending",
  )
  state.applied += repair
  state.evidenceEntries += childRepairWedgeEvidenceMap(repair, state.clock)
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
  state.evidenceEntries += childRepairWedgeEvidenceMap(repair, state.clock)
}

fun childRepairWedgeEvidenceMap(repair: GoalRunnerAppliedRepair, clock: Clock): Map<String, Any?> = linkedMapOf(
  "wedge_class" to repair.wedgeClass.wireValue,
  "field" to repair.field,
  "prior_value" to repair.priorValue,
  "new_value" to repair.newValue,
  "subtask_id" to repair.subtaskId,
  "workflow_id" to repair.workflowId,
  "repaired_at" to clock.instant().toString(),
)

fun continuationArtifactFromMap(artifacts: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationArtifact? {
  val raw = JsonSupport.anyToStringAnyMap(artifacts[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY])
    ?: return null
  return FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(raw)
}

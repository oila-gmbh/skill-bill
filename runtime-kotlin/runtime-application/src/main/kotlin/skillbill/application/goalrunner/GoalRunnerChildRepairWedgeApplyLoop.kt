package skillbill.application.goalrunner

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.goalrunner.model.GoalRunnerAppliedRepair
import skillbill.application.goalrunner.model.GoalRunnerChildRepairApplyRequest
import skillbill.application.goalrunner.model.GoalRunnerChildRepairApplyResult
import skillbill.application.goalrunner.model.GoalRunnerWedgeClass
import skillbill.application.workflow.WorkflowFamily
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection

internal class GoalRunnerChildRepairWedgeApplyLoop(
  private val engine: WorkflowEngine,
  private val gitOperations: WorkflowGitOperations,
  private val wedgeDiagnosis: GoalRunnerChildRepairWedgeDiagnosis,
  private val decompositionManifestValidator: DecompositionManifestValidator?,
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
    val context = unreachableReviewRepairContext(
      UnreachableReviewRepairLookup(
        wedgeClass = wedgeClass,
        wedgeDiagnosis = wedgeDiagnosis,
        repoRoot = state.request.repoRoot,
        gitOperations = gitOperations,
        review = state.workingReview,
        continuation = state.workingContinuation,
      ),
    ) ?: return
    applyUnreachableReviewRepairToState(wedgeClass, state, context)
  }

  internal class ApplyState(
    val request: GoalRunnerChildRepairApplyRequest,
    record: WorkflowStateSnapshot,
    artifacts: Map<String, Any?>,
    workingContinuation: FeatureTaskRuntimeGoalContinuationArtifact?,
    workingReview: GoalSubtaskReviewState?,
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

package skillbill.application.featuretask

import skillbill.application.model.ContinuationRead
import skillbill.application.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.model.FeatureTaskRuntimePreparation
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants

internal class FeatureTaskRuntimeRunPreparation(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  private val continuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  private val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
) {
  fun prepare(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimePreparation {
    val persistedInvariants = runInvariantsStore.resolve(request.workflowId, request.dbPathOverride)
    val reportInvariants = persistedInvariants ?: request.runInvariants
    return when (
      val initial = continuationRecorder.readContinuation(request, "Goal-continuation review persistence is malformed")
    ) {
      is ContinuationRead.Failure -> blocked(request, reportInvariants, initial.reason)
      ContinuationRead.None -> prepareWithContinuation(request, persistedInvariants, reportInvariants, null)
      is ContinuationRead.Available -> prepareWithContinuation(request, persistedInvariants, reportInvariants, initial)
    }
  }

  private fun prepareWithContinuation(
    request: FeatureTaskRuntimeRunRequest,
    persistedInvariants: FeatureTaskRuntimeRunInvariants?,
    reportInvariants: FeatureTaskRuntimeRunInvariants,
    initial: ContinuationRead.Available?,
  ): FeatureTaskRuntimePreparation {
    val selectedMode = selectedReviewMode(request, initial?.continuation)
    val policyConflict = continuationPolicyConflict(request, persistedInvariants, initial, selectedMode)
    if (policyConflict != null) {
      return blocked(request, reportInvariants, policyConflict)
    }
    recorder.ensureWorkflowOpen(request.workflowId, request.sessionId, request.dbPathOverride, request.issueKey)
    if (initial == null && request.goalContinuation != null && !recordContinuation(request, selectedMode)) {
      return blocked(
        request,
        reportInvariants,
        "Goal-continuation child state could not be persisted before freezing run invariants.",
      )
    }
    return when (
      val resolved = continuationRecorder.readContinuation(
        request,
        "Goal-continuation review persistence is malformed after initialization",
      )
    ) {
      is ContinuationRead.Failure -> blocked(request, reportInvariants, resolved.reason)
      ContinuationRead.None -> freezeRunInvariants(request, persistedInvariants, selectedMode, null)
      is ContinuationRead.Available -> freezeRunInvariants(request, persistedInvariants, selectedMode, resolved)
    }
  }

  private fun continuationPolicyConflict(
    request: FeatureTaskRuntimeRunRequest,
    persistedInvariants: FeatureTaskRuntimeRunInvariants?,
    initial: ContinuationRead.Available?,
    selectedMode: CodeReviewExecutionMode,
  ): String? = when {
    initial != null -> persistedContinuationConflict(request, persistedInvariants, initial, selectedMode)
    request.goalContinuation != null -> newGoalContinuationConflict(request, selectedMode)
    else -> requestedResumeModeConflict(request, persistedInvariants)
  } ?: goalContinuationInvariantConflict(request, persistedInvariants, initial, selectedMode)
    ?: persistedAgentAddonSelectionConflict(request, persistedInvariants)

  private fun persistedAgentAddonSelectionConflict(
    request: FeatureTaskRuntimeRunRequest,
    persistedInvariants: FeatureTaskRuntimeRunInvariants?,
  ): String? = persistedInvariants
    ?.agentAddonSelection
    ?.takeIf { it != request.agentAddonSelection.persisted }
    ?.let { "Cannot drop or replace the workflow's durable agent add-on selection on resume." }

  private fun persistedContinuationConflict(
    request: FeatureTaskRuntimeRunRequest,
    persistedInvariants: FeatureTaskRuntimeRunInvariants?,
    initial: ContinuationRead.Available,
    selectedMode: CodeReviewExecutionMode,
  ): String? = goalContinuationConflict(request, initial.continuation, initial.baseline)
    ?: persistedInvariants
      ?.takeIf { it.codeReviewMode != selectedMode }
      ?.let { "Goal-continuation review policy does not match the workflow's durable code-review mode." }

  private fun requestedResumeModeConflict(
    request: FeatureTaskRuntimeRunRequest,
    persistedInvariants: FeatureTaskRuntimeRunInvariants?,
  ): String? = persistedInvariants
    ?.takeIf { request.requestedCodeReviewMode != null && it.codeReviewMode != request.requestedCodeReviewMode }
    ?.let {
      "Cannot change code-review mode on resume: workflow '${request.workflowId}' is pinned to " +
        "'${it.codeReviewMode.wireValue}', not '${request.requestedCodeReviewMode?.wireValue}'."
    }

  private fun goalContinuationInvariantConflict(
    request: FeatureTaskRuntimeRunRequest,
    persistedInvariants: FeatureTaskRuntimeRunInvariants?,
    initial: ContinuationRead.Available?,
    selectedMode: CodeReviewExecutionMode,
  ): String? = persistedInvariants
    ?.takeIf { hasGoalContinuation(initial, request) && it.codeReviewMode != selectedMode }
    ?.let { "Goal-continuation review policy does not match the workflow's durable code-review mode." }

  private fun recordContinuation(
    request: FeatureTaskRuntimeRunRequest,
    selectedMode: CodeReviewExecutionMode,
  ): Boolean {
    val context = requireNotNull(request.goalContinuation)
    return continuationRecorder.recordGoalContinuationState(
      request = GoalContinuationStateRecordRequest(
        workflowId = request.workflowId,
        continuation = FeatureTaskRuntimeGoalContinuationArtifact(
          issueKey = context.parentIssueKey,
          subtaskId = context.subtaskId,
          suppressPr = context.suppressPr,
          goalBranch = context.goalBranch,
          parentWorkflowId = context.parentWorkflowId,
          codeReviewMode = selectedMode,
          parallelReviewAgent = context.parallelReviewAgent ?: request.parallelReviewAgent,
          agentAddonSelection = context.agentAddonSelection.takeUnless { it.entries.isEmpty() }
            ?: request.agentAddonSelection.persisted,
        ),
        reviewBaseline = requireNotNull(context.reviewBaseline),
      ),
      dbOverride = request.dbPathOverride,
    )
  }

  private fun freezeRunInvariants(
    request: FeatureTaskRuntimeRunRequest,
    persistedInvariants: FeatureTaskRuntimeRunInvariants?,
    selectedMode: CodeReviewExecutionMode,
    continuation: ContinuationRead.Available?,
  ): FeatureTaskRuntimePreparation {
    val proposed = persistedInvariants ?: request.runInvariants.copy(
      codeReviewMode = continuation?.continuation?.codeReviewMode ?: selectedMode,
    )
    val durable = runInvariantsStore.resolve(request.workflowId, request.dbPathOverride, proposed) ?: proposed
    val durableContinuation = continuation?.continuation
    if (durableContinuation != null && durable.codeReviewMode != durableContinuation.codeReviewMode) {
      return blocked(
        request,
        durable,
        "Goal-continuation review policy does not match the workflow's durable code-review mode.",
      )
    }
    return FeatureTaskRuntimePreparation.Prepared(
      request.copy(
        runInvariants = durable,
        parallelReviewAgent = durableContinuation?.parallelReviewAgent ?: request.parallelReviewAgent,
        goalContinuation = durableContinuation?.let { continuationArtifact ->
          goalContinuationContext(continuationArtifact, requireNotNull(continuation).baseline, request)
        },
      ),
    )
  }
}

private fun hasGoalContinuation(initial: ContinuationRead.Available?, request: FeatureTaskRuntimeRunRequest): Boolean =
  initial != null || request.goalContinuation != null

private fun selectedReviewMode(
  request: FeatureTaskRuntimeRunRequest,
  continuation: FeatureTaskRuntimeGoalContinuationArtifact?,
): CodeReviewExecutionMode = continuation?.codeReviewMode
  ?: request.goalContinuation?.codeReviewMode
  ?: request.requestedCodeReviewMode
  ?: request.runInvariants.codeReviewMode

private fun FeatureTaskRuntimeGoalContinuationRecorder.readContinuation(
  request: FeatureTaskRuntimeRunRequest,
  persistencePrefix: String,
): ContinuationRead = runCatching { continuation(request.workflowId, request.dbPathOverride) }.fold(
  onSuccess = { continuation ->
    continuation?.let { readReviewBaseline(request, it) } ?: ContinuationRead.None
  },
  onFailure = { error -> ContinuationRead.Failure("$persistencePrefix: ${error.message.orEmpty()}") },
)

private fun FeatureTaskRuntimeGoalContinuationRecorder.readReviewBaseline(
  request: FeatureTaskRuntimeRunRequest,
  continuation: FeatureTaskRuntimeGoalContinuationArtifact,
): ContinuationRead = runCatching { reviewState(request.workflowId, request.dbPathOverride) }.fold(
  onSuccess = { state ->
    state?.let {
      ContinuationRead.Available(
        continuation,
        GoalSubtaskReviewBaseline(it.reviewBaseSha, it.baselineUntrackedPaths),
      )
    } ?: ContinuationRead.Failure(
      "Goal-continuation review state is missing; refusing to recreate its immutable review baseline.",
    )
  },
  onFailure = { error ->
    ContinuationRead.Failure(
      "Goal-continuation review state or durable raw evidence is malformed: ${error.message.orEmpty()}",
    )
  },
)

private fun goalContinuationContext(
  continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  baseline: GoalSubtaskReviewBaseline,
  request: FeatureTaskRuntimeRunRequest,
): FeatureTaskRuntimeGoalContinuationContext = FeatureTaskRuntimeGoalContinuationContext(
  parentIssueKey = continuation.issueKey,
  subtaskId = continuation.subtaskId,
  goalBranch = continuation.goalBranch,
  suppressPr = continuation.suppressPr,
  parentWorkflowId = continuation.parentWorkflowId,
  lastResumableStep = request.goalContinuation?.lastResumableStep,
  codeReviewMode = continuation.codeReviewMode,
  parallelReviewAgent = continuation.parallelReviewAgent,
  reviewBaseline = baseline,
  agentAddonSelection = continuation.agentAddonSelection,
)

private fun blocked(
  request: FeatureTaskRuntimeRunRequest,
  invariants: FeatureTaskRuntimeRunInvariants,
  reason: String,
): FeatureTaskRuntimePreparation.PreparationBlocked = FeatureTaskRuntimePreparation.PreparationBlocked(
  goalContinuationPolicyBlockedReport(request, invariants, reason),
)

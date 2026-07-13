package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants

internal sealed interface FeatureTaskRuntimePreparation {
  data class Ready(val request: FeatureTaskRuntimeRunRequest) : FeatureTaskRuntimePreparation

  data class Blocked(val report: FeatureTaskRuntimeRunReport.Blocked) : FeatureTaskRuntimePreparation
}

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
      is ContinuationRead.Failed -> blocked(request, reportInvariants, initial.reason)
      ContinuationRead.None -> prepareWithContinuation(request, persistedInvariants, reportInvariants, null)
      is ContinuationRead.Ready -> prepareWithContinuation(request, persistedInvariants, reportInvariants, initial)
    }
  }

  private fun prepareWithContinuation(
    request: FeatureTaskRuntimeRunRequest,
    persistedInvariants: FeatureTaskRuntimeRunInvariants?,
    reportInvariants: FeatureTaskRuntimeRunInvariants,
    initial: ContinuationRead.Ready?,
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
      is ContinuationRead.Failed -> blocked(request, reportInvariants, resolved.reason)
      ContinuationRead.None -> freezeRunInvariants(request, persistedInvariants, selectedMode, null)
      is ContinuationRead.Ready -> freezeRunInvariants(request, persistedInvariants, selectedMode, resolved)
    }
  }

  private fun continuationPolicyConflict(
    request: FeatureTaskRuntimeRunRequest,
    persistedInvariants: FeatureTaskRuntimeRunInvariants?,
    initial: ContinuationRead.Ready?,
    selectedMode: CodeReviewExecutionMode,
  ): String? = when {
    initial != null -> persistedContinuationConflict(request, persistedInvariants, initial, selectedMode)
    request.goalContinuation != null -> newGoalContinuationConflict(request, selectedMode)
    else -> requestedResumeModeConflict(request, persistedInvariants)
  } ?: goalContinuationInvariantConflict(request, persistedInvariants, initial, selectedMode)

  private fun persistedContinuationConflict(
    request: FeatureTaskRuntimeRunRequest,
    persistedInvariants: FeatureTaskRuntimeRunInvariants?,
    initial: ContinuationRead.Ready,
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
    initial: ContinuationRead.Ready?,
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
    continuation: ContinuationRead.Ready?,
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
    return FeatureTaskRuntimePreparation.Ready(
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

private fun hasGoalContinuation(initial: ContinuationRead.Ready?, request: FeatureTaskRuntimeRunRequest): Boolean =
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
  onFailure = { error -> ContinuationRead.Failed("$persistencePrefix: ${error.message.orEmpty()}") },
)

private fun FeatureTaskRuntimeGoalContinuationRecorder.readReviewBaseline(
  request: FeatureTaskRuntimeRunRequest,
  continuation: FeatureTaskRuntimeGoalContinuationArtifact,
): ContinuationRead = runCatching { reviewState(request.workflowId, request.dbPathOverride) }.fold(
  onSuccess = { state ->
    state?.let {
      ContinuationRead.Ready(
        continuation,
        GoalSubtaskReviewBaseline(it.reviewBaseSha, it.baselineUntrackedPaths),
      )
    } ?: ContinuationRead.Failed(
      "Goal-continuation review state is missing; refusing to recreate its immutable review baseline.",
    )
  },
  onFailure = { error ->
    ContinuationRead.Failed(
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
)

private fun blocked(
  request: FeatureTaskRuntimeRunRequest,
  invariants: FeatureTaskRuntimeRunInvariants,
  reason: String,
): FeatureTaskRuntimePreparation.Blocked = FeatureTaskRuntimePreparation.Blocked(
  goalContinuationPolicyBlockedReport(request, invariants, reason),
)

private sealed interface ContinuationRead {
  data object None : ContinuationRead

  data class Ready(
    val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
    val baseline: GoalSubtaskReviewBaseline,
  ) : ContinuationRead

  data class Failed(val reason: String) : ContinuationRead
}

package skillbill.application.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.featuretask.model.GoalSubtaskReviewInputBlocked
import skillbill.application.featuretask.model.GoalSubtaskReviewInputPreparation
import skillbill.application.featuretask.model.GoalSubtaskReviewInputReady
import skillbill.application.workflow.model.WorkflowFamily
import skillbill.error.FeatureTaskRuntimeSubtaskCommitReconciliationError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputFailureReason
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.gitops.recoverGoalSubtaskReviewBaseline
import skillbill.workflow.goal.model.GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointIdentitiesFromArtifact
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

class FeatureTaskRuntimeGoalReviewInputBuilder(
  internal val database: DatabaseSessionFactory,
  private val patcher: FeatureTaskRuntimeGoalContinuationArtifactPatcher,
  private val persistGoalReviewInput: (String, GoalSubtaskReviewInput, String?) -> GoalSubtaskReviewState?,
) {
  fun loadGoalReviewDurable(
    workflowId: String,
    scope: FeatureTaskRuntimeGoalContinuationRecorder.GoalReviewInputScope,
  ): Pair<GoalSubtaskReviewState, FeatureTaskRuntimeGoalContinuationArtifact>? =
    database.read(scope.dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
      val artifacts = decodeArtifacts(record.artifactsJson)
      val state = reviewStateFromArtifacts(artifacts) ?: return@read null
      val continuation = continuationFromArtifacts(artifacts) ?: return@read null
      state to continuation
    }

  fun buildGoalReviewInput(
    workflowId: String,
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
    scope: FeatureTaskRuntimeGoalContinuationRecorder.GoalReviewInputScope,
  ): GoalSubtaskReviewInputPreparation {
    val durable = loadGoalReviewDurableOrThrow(workflowId, scope)
      ?: return GoalSubtaskReviewInputPreparation.MissingState
    return buildGoalReviewInputFromDurable(workflowId, gitOperations, repoRoot, scope, durable)
  }

  private fun buildGoalReviewInputFromDurable(
    workflowId: String,
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
    scope: FeatureTaskRuntimeGoalContinuationRecorder.GoalReviewInputScope,
    durable: Pair<GoalSubtaskReviewState, FeatureTaskRuntimeGoalContinuationArtifact>,
  ): GoalSubtaskReviewInputPreparation {
    val (state, continuation) = durable
    val activeParentSha = activeSubtaskCheckpointParentOrThrow(workflowId, continuation, scope.dbOverride)
    if (state.completedPassCount == 0 && state.remediationBaseSha == null && activeParentSha == null) {
      return GoalSubtaskReviewInputBlocked(
        "Goal-subtask review cannot select its first-pass base: the active subtask checkpoint has no recorded parent " +
          "SHA. Resume after checkpoint identity reconciliation proves the committed revision pair.",
      )
    }
    val (selectedBaseline, failedField) = selectedGoalReviewBaseline(state, activeParentSha)
    val result = gitOperations.buildGoalSubtaskReviewInput(
      repoRoot,
      selectedBaseline,
      continuation.goalBranch,
    )
    val recovery = if (result.ok) {
      null
    } else {
      recoverGoalReviewInput(
        GoalReviewInputRecoveryRequest(
          workflowId = workflowId,
          state = state,
          continuation = continuation,
          failureReason = result.failureReason,
          failureMessage = result.error,
          failedBaseSha = selectedBaseline.reviewBaseSha,
          failedField = failedField,
          scope = scope,
          execution = GoalReviewInputRecoveryExecution(gitOperations, repoRoot, scope.dbOverride),
        ),
      )
    }
    val input = goalReviewInputFromBuildResult(result, recovery)
      ?: return goalReviewBlockedPreparation(result, recovery)
    val persisted = persistGoalReviewInput(workflowId, input, scope.dbOverride)
      ?: return GoalSubtaskReviewInputPreparation.MissingState
    return GoalSubtaskReviewInputReady(persisted, input)
  }

  private fun loadGoalReviewDurableOrThrow(
    workflowId: String,
    scope: FeatureTaskRuntimeGoalContinuationRecorder.GoalReviewInputScope,
  ): Pair<GoalSubtaskReviewState, FeatureTaskRuntimeGoalContinuationArtifact>? = try {
    loadGoalReviewDurable(workflowId, scope)
  } catch (error: CancellationException) {
    throw error
  } catch (error: FeatureTaskRuntimeSubtaskCommitReconciliationError) {
    throw error
  } catch (error: IllegalStateException) {
    throw goalReviewInputReconciliationFailure(
      workflowId,
      null,
      "goal-subtask review state could not be read (${error.message.orEmpty()})",
      error,
    )
  }

  private fun activeSubtaskCheckpointParentOrThrow(
    workflowId: String,
    continuation: FeatureTaskRuntimeGoalContinuationArtifact,
    dbOverride: String?,
  ): String? = try {
    activeSubtaskCheckpointParent(workflowId, continuation, dbOverride)
  } catch (error: CancellationException) {
    throw error
  } catch (error: FeatureTaskRuntimeSubtaskCommitReconciliationError) {
    throw error
  } catch (error: IllegalStateException) {
    throw goalReviewInputReconciliationFailure(
      workflowId,
      continuation,
      "active subtask checkpoint parent could not be read (${error.message.orEmpty()})",
      error,
    )
  }

  internal fun recoverGoalReviewInput(request: GoalReviewInputRecoveryRequest): GoalReviewInputRecovery {
    val failureReason = request.failureReason
    if (failureReason == null ||
      failureReason !in recoverableReviewBaseFailures ||
      !request.state.canRecoverReviewBase()
    ) {
      return GoalReviewInputRecovery.Ineligible
    }
    return recoverEligibleGoalReviewInput(request, failureReason)
  }

  private fun recoverEligibleGoalReviewInput(
    request: GoalReviewInputRecoveryRequest,
    failureReason: String,
  ): GoalReviewInputRecovery {
    val recovered = request.execution.gitOperations.recoverGoalSubtaskReviewBaseline(
      request.execution.repoRoot,
      GoalSubtaskReviewBaselineRecoveryRequest(
        unreachableSha = request.failedBaseSha,
        failureReason = failureReason,
      ),
      request.continuation.goalBranch,
    )
    if (!recovered.ok) {
      return failGoalReviewInputRecovery(
        request.workflowId,
        request.continuation,
        "goal-subtask review baseline recovery failed (${recovered.error})",
        IllegalStateException(recovered.error.ifBlank { "Git baseline recovery failed" }),
      )
    }
    val recoveredBaseline = recovered.baseline ?: return failGoalReviewInputRecovery(
      request.workflowId,
      request.continuation,
      "goal-subtask review baseline recovery returned no reachable base",
      IllegalStateException("Git baseline recovery returned no baseline"),
    )
    val input = rebuildRecoveredGoalReviewInput(request, recoveredBaseline)
    val persisted = persistRecoveredGoalReviewBaseline(request, recoveredBaseline, input, failureReason)
    if (persisted == null) {
      return failGoalReviewInputRecovery(
        request.workflowId,
        request.continuation,
        "goal-subtask review baseline recovery could not persist its replacement state",
        IllegalStateException("workflow row or review state disappeared while persisting baseline recovery"),
      )
    }
    return GoalReviewInputRecovery.Recovered(input)
  }

  private fun rebuildRecoveredGoalReviewInput(
    request: GoalReviewInputRecoveryRequest,
    recoveredBaseline: GoalSubtaskReviewBaseline,
  ): GoalSubtaskReviewInput {
    val rebuilt = request.execution.gitOperations.buildGoalSubtaskReviewInput(
      request.execution.repoRoot,
      recoveredBaseline,
      request.continuation.goalBranch,
    )
    if (!rebuilt.ok) {
      return failGoalReviewInputRecovery(
        request.workflowId,
        request.continuation,
        "recovered goal-subtask review base '${recoveredBaseline.reviewBaseSha}' could not materialize review input",
        IllegalStateException(rebuilt.error.ifBlank { request.failureMessage }),
      )
    }
    return rebuilt.input ?: failGoalReviewInputRecovery(
      request.workflowId,
      request.continuation,
      "recovered goal-subtask review input was missing after Git materialization",
      IllegalStateException("Git review input materialization returned no input"),
    )
  }

  private fun persistRecoveredGoalReviewBaseline(
    request: GoalReviewInputRecoveryRequest,
    recoveredBaseline: GoalSubtaskReviewBaseline,
    input: GoalSubtaskReviewInput,
    failureReason: GoalSubtaskReviewInputFailureReason,
  ): GoalSubtaskReviewState? = database.transaction(request.execution.dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
      ?: return@transaction null
    val artifacts = decodeArtifacts(record.artifactsJson)
    val latest = reviewStateFromArtifacts(artifacts) ?: return@transaction null
    check(latest == request.state && latest.canRecoverReviewBase()) {
      "Goal-subtask review base can be recovered only while disposition is still pending."
    }
    val replaced = replacedReviewState(request, latest, recoveredBaseline)
    check(
      input.reviewBaseSha == replaced.reviewBaseSha ||
        input.reviewBaseSha == replaced.remediationBaseSha,
    ) {
      "Recovered goal-subtask review input does not match the replacement baseline."
    }
    val evidenceEntry = linkedMapOf<String, Any?>(
      "original_sha" to request.failedBaseSha,
      "replacement_sha" to recoveredBaseline.reviewBaseSha,
      "repointed_field" to request.failedField.wireValue,
      "failure_reason" to failureReason.name.lowercase(),
      "failure_message" to request.failureMessage,
      "goal_branch" to request.continuation.goalBranch,
    )
    val priorEvidence = (artifacts[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY] as? List<*>).orEmpty()
    patcher.save(
      record,
      unitOfWork.workflowStates,
      mapOf(
        GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to replaced.toArtifactMap(),
        GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY to input.toArtifactMap(),
        GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY to priorEvidence + evidenceEntry,
      ),
    )
    replaced
  }

  private fun replacedReviewState(
    request: GoalReviewInputRecoveryRequest,
    latest: GoalSubtaskReviewState,
    recoveredBaseline: GoalSubtaskReviewBaseline,
  ): GoalSubtaskReviewState = when (request.failedField) {
    GoalReviewBaseField.REMEDIATION_BASE -> latest.copy(
      remediationBaseSha = recoveredBaseline.reviewBaseSha,
      reviewInputArtifact = GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY,
    )
    GoalReviewBaseField.REVIEW_BASE -> latest.copy(
      reviewBaseSha = recoveredBaseline.reviewBaseSha,
      reviewInputArtifact = GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY,
    )
  }
}

private fun goalReviewInputReconciliationFailure(
  workflowId: String,
  continuation: FeatureTaskRuntimeGoalContinuationArtifact?,
  reason: String,
  cause: Throwable,
) = FeatureTaskRuntimeSubtaskCommitReconciliationError(
  workflowId = workflowId,
  issueKey = continuation?.issueKey ?: "unknown",
  subtaskId = continuation?.subtaskId?.toString() ?: "unknown",
  reason = reason,
  cause = cause,
)

private fun FeatureTaskRuntimeGoalReviewInputBuilder.activeSubtaskCheckpointParent(
  workflowId: String,
  continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  dbOverride: String?,
): String? = database.read(dbOverride) { unitOfWork ->
  val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
  val checkpoints = featureTaskRuntimeCheckpointIdentitiesFromArtifact(
    decodeArtifacts(record.artifactsJson)[FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY],
  )
  checkpoints
    .filter {
      it.issueKey == continuation.issueKey &&
        it.subtaskId == continuation.subtaskId.toString() &&
        it.loopId == null
    }
    .maxByOrNull(FeatureTaskRuntimeCheckpointIdentity::sequenceNumber)
    ?.parentSha
    ?.trim()
    ?.takeIf(String::isNotBlank)
}

internal data class GoalReviewInputRecoveryRequest(
  val workflowId: String,
  val state: GoalSubtaskReviewState,
  val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  val failureReason: GoalSubtaskReviewInputFailureReason?,
  val failureMessage: String,
  val failedBaseSha: String,
  val failedField: GoalReviewBaseField,
  val scope: FeatureTaskRuntimeGoalContinuationRecorder.GoalReviewInputScope,
  val execution: GoalReviewInputRecoveryExecution,
)

internal sealed interface GoalReviewInputRecovery {
  class Recovered(val input: GoalSubtaskReviewInput) : GoalReviewInputRecovery
  data object Ineligible : GoalReviewInputRecovery
}

internal data class GoalReviewInputRecoveryExecution(
  val gitOperations: WorkflowGitOperations,
  val repoRoot: Path,
  val dbOverride: String?,
)

private val recoverableReviewBaseFailures: Set<GoalSubtaskReviewInputFailureReason> = setOf(
  GoalSubtaskReviewInputFailureReason.BASE_MISSING,
  GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR,
)

internal fun selectedGoalReviewBaseline(
  state: GoalSubtaskReviewState,
  activeParentSha: String? = null,
): Pair<GoalSubtaskReviewBaseline, GoalReviewBaseField> {
  val remediationBaseSha = state.remediationBaseSha
    ?.takeIf { state.completedPassCount >= 1 && state.reservedPassNumber == null }
  return if (remediationBaseSha != null) {
    GoalSubtaskReviewBaseline(remediationBaseSha, allowNonAncestorBase = true) to
      GoalReviewBaseField.REMEDIATION_BASE
  } else {
    GoalSubtaskReviewBaseline(activeParentSha ?: state.reviewBaseSha) to GoalReviewBaseField.REVIEW_BASE
  }
}

internal fun FeatureTaskRuntimeGoalReviewInputBuilder.goalReviewInputFromBuildResult(
  result: GoalSubtaskReviewInputResult,
  recovery: GoalReviewInputRecovery?,
): GoalSubtaskReviewInput? = when {
  result.ok -> requireNotNull(result.input)
  recovery is GoalReviewInputRecovery.Recovered -> recovery.input
  else -> null
}

internal fun goalReviewBlockedPreparation(
  result: GoalSubtaskReviewInputResult,
  recovery: GoalReviewInputRecovery?,
): GoalSubtaskReviewInputPreparation = GoalSubtaskReviewInputBlocked(
  when (recovery) {
    is GoalReviewInputRecovery.Ineligible, null -> result.error
    is GoalReviewInputRecovery.Recovered -> error("blocked preparation requested for recovered input")
  },
)

private fun failGoalReviewInputRecovery(
  workflowId: String,
  continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  reason: String,
  cause: Throwable,
): Nothing = throw goalReviewInputReconciliationFailure(workflowId, continuation, reason, cause)

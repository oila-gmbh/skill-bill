package skillbill.application.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.workflow.WorkflowFamily
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputFailureReason
import skillbill.ports.workflow.gitops.recoverGoalSubtaskReviewBaseline
import skillbill.workflow.goal.model.GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import java.nio.file.Path

internal class FeatureTaskRuntimeGoalReviewInputBuilder(
  private val database: DatabaseSessionFactory,
  private val patcher: FeatureTaskRuntimeGoalContinuationArtifactPatcher,
  private val persistGoalReviewInput: (String, GoalSubtaskReviewInput, String?) -> GoalSubtaskReviewState?,
) {
  fun buildGoalReviewInput(
    workflowId: String,
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
    scope: FeatureTaskRuntimeGoalContinuationRecorder.GoalReviewInputScope,
  ): GoalSubtaskReviewInputPreparation {
    val durable = database.read(scope.dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
      val artifacts = decodeArtifacts(record.artifactsJson)
      val state = reviewStateFromArtifacts(artifacts)
        ?: return@read null
      val continuation = continuationFromArtifacts(artifacts)
        ?: return@read null
      state to continuation
    } ?: return GoalSubtaskReviewInputPreparation.MissingState
    val (state, continuation) = durable
    val exclusions = scope.scopedUntrackedExclusions ?: state.baselineUntrackedPaths
    val remediationBaseline = state.remediationBaseSha
      ?.takeIf { state.completedPassCount >= 1 && state.reservedPassNumber == null }
      ?.let { preFixSha -> GoalSubtaskReviewBaseline(preFixSha, exclusions, scope.ownedPathspec) }
    val selectedBaseline = remediationBaseline
      ?: GoalSubtaskReviewBaseline(state.reviewBaseSha, exclusions, scope.ownedPathspec)
    val failedField = if (remediationBaseline != null) {
      GoalReviewBaseField.REMEDIATION_BASE
    } else {
      GoalReviewBaseField.REVIEW_BASE
    }
    val result = gitOperations.buildGoalSubtaskReviewInput(
      repoRoot,
      selectedBaseline,
      continuation.goalBranch,
    )
    val input = if (result.ok) {
      requireNotNull(result.input)
    } else {
      when (
        val recovery = recoverGoalReviewInput(
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
      ) {
        is GoalReviewInputRecovery.Recovered -> recovery.input
        is GoalReviewInputRecovery.Failed -> return GoalSubtaskReviewInputBlocked(recovery.reason)
        GoalReviewInputRecovery.Ineligible -> return GoalSubtaskReviewInputBlocked(result.error)
      }
    }
    val persisted = persistGoalReviewInput(workflowId, input, scope.dbOverride)
      ?: return GoalSubtaskReviewInputPreparation.MissingState
    return GoalSubtaskReviewInputReady(persisted, input)
  }

  @Suppress("LongMethod")
  private fun recoverGoalReviewInput(request: GoalReviewInputRecoveryRequest): GoalReviewInputRecovery {
    val failureReason = request.failureReason
    if (failureReason == null ||
      failureReason !in recoverableReviewBaseFailures ||
      !request.state.canRecoverReviewBase()
    ) {
      return GoalReviewInputRecovery.Ineligible
    }
    val exclusions = request.scope.scopedUntrackedExclusions ?: request.state.baselineUntrackedPaths
    val recovered = request.execution.gitOperations.recoverGoalSubtaskReviewBaseline(
      request.execution.repoRoot,
      GoalSubtaskReviewBaselineRecoveryRequest(
        unreachableSha = request.failedBaseSha,
        failureReason = failureReason,
        baselineUntrackedPaths = exclusions,
        ownedPathspec = request.scope.ownedPathspec,
      ),
      request.continuation.goalBranch,
    )
    if (!recovered.ok) {
      return GoalReviewInputRecovery.Failed(
        recovered.error.ifBlank {
          "Goal-subtask review baseline recovery could not find a reachable base for unreachable sha " +
            "'${request.failedBaseSha}' on branch '${request.continuation.goalBranch}'."
        },
      )
    }
    val recoveredBaseline = requireNotNull(recovered.baseline)
    val rebuilt = request.execution.gitOperations.buildGoalSubtaskReviewInput(
      request.execution.repoRoot,
      recoveredBaseline,
      request.continuation.goalBranch,
    )
    check(rebuilt.ok) {
      "Recovered goal-subtask review base '${recoveredBaseline.reviewBaseSha}' could not materialize review input " +
        "after replacing incompatible base '${request.failedBaseSha}': " +
        rebuilt.error.ifBlank { request.failureMessage }
    }
    val input = requireNotNull(rebuilt.input)
    val persisted = database.transaction(request.execution.dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
        ?: return@transaction null
      val artifacts = decodeArtifacts(record.artifactsJson)
      val latest = reviewStateFromArtifacts(artifacts) ?: return@transaction null
      check(latest == request.state && latest.canRecoverReviewBase()) {
        "Goal-subtask review base can be recovered only while disposition is still pending."
      }
      val replaced = when (request.failedField) {
        GoalReviewBaseField.REMEDIATION_BASE -> latest.copy(
          remediationBaseSha = recoveredBaseline.reviewBaseSha,
          reviewInputArtifact = GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY,
        )
        GoalReviewBaseField.REVIEW_BASE -> latest.copy(
          reviewBaseSha = recoveredBaseline.reviewBaseSha,
          baselineUntrackedPaths = recoveredBaseline.baselineUntrackedPaths.distinct().sorted(),
          reviewInputArtifact = GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY,
        )
      }
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
    return if (persisted != null) {
      GoalReviewInputRecovery.Recovered(input)
    } else {
      GoalReviewInputRecovery.Ineligible
    }
  }
}

private data class GoalReviewInputRecoveryRequest(
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

private sealed interface GoalReviewInputRecovery {
  class Recovered(val input: GoalSubtaskReviewInput) : GoalReviewInputRecovery
  class Failed(val reason: String) : GoalReviewInputRecovery
  data object Ineligible : GoalReviewInputRecovery
}

private data class GoalReviewInputRecoveryExecution(
  val gitOperations: WorkflowGitOperations,
  val repoRoot: Path,
  val dbOverride: String?,
)

private val recoverableReviewBaseFailures: Set<GoalSubtaskReviewInputFailureReason> = setOf(
  GoalSubtaskReviewInputFailureReason.BASE_MISSING,
  GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR,
)

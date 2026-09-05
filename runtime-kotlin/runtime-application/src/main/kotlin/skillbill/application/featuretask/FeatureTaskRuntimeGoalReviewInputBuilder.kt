package skillbill.application.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.featuretask.model.GoalSubtaskReviewInputBlocked
import skillbill.application.featuretask.model.GoalSubtaskReviewInputPreparation
import skillbill.application.featuretask.model.GoalSubtaskReviewInputReady
import skillbill.application.featuretask.model.PortableUnreachableReviewBaseRecovery
import skillbill.application.featuretask.model.PortableUnreachableReviewBaseRecoveryCommand
import skillbill.application.workflow.model.WorkflowFamily
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputFailureReason
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.gitops.recoverGoalSubtaskReviewBaseline
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.goal.model.GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import java.nio.file.Path

class FeatureTaskRuntimeGoalReviewInputBuilder(
  private val database: DatabaseSessionFactory,
  private val patcher: FeatureTaskRuntimeGoalContinuationArtifactPatcher,
  private val persistGoalReviewInput: (String, GoalSubtaskReviewInput, String?) -> GoalSubtaskReviewState?,
  private val engine: WorkflowEngine,
  private val unreachableReviewBaseRecovery: PortableUnreachableReviewBaseRecovery,
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
    val durable = loadGoalReviewDurable(workflowId, scope) ?: return GoalSubtaskReviewInputPreparation.MissingState
    val (state, continuation) = durable
    val (selectedBaseline, failedField) = selectedGoalReviewBaseline(state, scope)
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

  internal fun recoverGoalReviewInput(request: GoalReviewInputRecoveryRequest): GoalReviewInputRecovery {
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
    val input = rebuildRecoveredGoalReviewInput(request, recoveredBaseline)
    val persisted = persistRecoveredGoalReviewBaseline(request, recoveredBaseline, input, failureReason)
    return if (persisted != null) {
      GoalReviewInputRecovery.Recovered(input)
    } else {
      GoalReviewInputRecovery.Ineligible
    }
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
    check(rebuilt.ok) {
      "Recovered goal-subtask review base '${recoveredBaseline.reviewBaseSha}' could not materialize " +
        "review input after replacing incompatible base '${request.failedBaseSha}': " +
        rebuilt.error.ifBlank { request.failureMessage }
    }
    return requireNotNull(rebuilt.input)
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
    recordPortableUnreachableBaseRecovery(request, unitOfWork.workflowStates, recoveredBaseline)
    replaced
  }

  private fun recordPortableUnreachableBaseRecovery(
    request: GoalReviewInputRecoveryRequest,
    workflowStates: WorkflowStateRepository,
    recoveredBaseline: GoalSubtaskReviewBaseline,
  ) {
    unreachableReviewBaseRecovery.record(
      PortableUnreachableReviewBaseRecoveryCommand(
        workflowStates = workflowStates,
        continuation = request.continuation,
        workflowId = request.workflowId,
        recoveredBaseline = recoveredBaseline,
        repoRoot = request.execution.repoRoot,
        engine = engine,
      ),
    )
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
      baselineUntrackedPaths = recoveredBaseline.baselineUntrackedPaths.distinct().sorted(),
      reviewInputArtifact = GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY,
    )
  }
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
  class Failed(val reason: String) : GoalReviewInputRecovery
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
  scope: FeatureTaskRuntimeGoalContinuationRecorder.GoalReviewInputScope,
): Pair<GoalSubtaskReviewBaseline, GoalReviewBaseField> {
  val exclusions = scope.scopedUntrackedExclusions ?: state.baselineUntrackedPaths
  val remediationBaseline = state.remediationBaseSha
    ?.takeIf { state.completedPassCount >= 1 && state.reservedPassNumber == null }
    ?.let { preFixSha -> GoalSubtaskReviewBaseline(preFixSha, exclusions, scope.ownedPathspec) }
  return if (remediationBaseline != null) {
    remediationBaseline to GoalReviewBaseField.REMEDIATION_BASE
  } else {
    GoalSubtaskReviewBaseline(state.reviewBaseSha, exclusions, scope.ownedPathspec) to GoalReviewBaseField.REVIEW_BASE
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
    is GoalReviewInputRecovery.Failed -> recovery.reason
    is GoalReviewInputRecovery.Ineligible, null -> result.error
    is GoalReviewInputRecovery.Recovered -> error("blocked preparation requested for recovered input")
  },
)

package skillbill.application.goalrunner

import skillbill.application.goalrunner.model.GoalRunnerRunEvent
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.goalrunner.planning.goalPlanningChildImportConflictBlockedReason
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.application.workflow.generateWorkflowId
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.goalrunner.model.GoalRunnerSelection
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.captureGoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

internal class GoalRunnerSubtaskLaunchPrepare(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val gitOperations: WorkflowGitOperations,
) {
  fun goalReviewBaseline(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalSubtaskReviewBaselineResult {
    val existingWorkflowId = state.manifest.workflowIdFor(subtaskId)
    if (existingWorkflowId != null) {
      return runCatching {
        outcomeStore.goalSubtaskReviewState(existingWorkflowId, request.dbPathOverride)
          ?.let { reviewState ->
            GoalSubtaskReviewBaselineResult(
              status = "ok",
              baseline = GoalSubtaskReviewBaseline(reviewState.reviewBaseSha, reviewState.baselineUntrackedPaths),
            )
          }
          ?: GoalSubtaskReviewBaselineResult(
            status = "error",
            error =
            "Goal-subtask review state is missing for existing child '$existingWorkflowId'; " +
              "refusing to recapture its immutable baseline.",
          )
      }.getOrElse { error ->
        GoalSubtaskReviewBaselineResult(
          status = "error",
          error =
          "Goal-subtask review persistence is malformed for existing child '$existingWorkflowId': " +
            error.message.orEmpty(),
        )
      }
    }
    val branch = state.manifest.branchPlanFor(subtaskId).branch.takeIf(String::isNotBlank)
      ?: state.manifest.featureBranch?.takeIf(String::isNotBlank)
      ?: return GoalSubtaskReviewBaselineResult(
        status = "error",
        error = "Goal subtask '$subtaskId' has no durable child branch for review baseline capture.",
      )
    return gitOperations.captureGoalSubtaskReviewBaseline(request.repoRoot, branch)
  }

  fun blockedReviewBaselineIteration(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    reason: String,
    request: GoalRunnerRunRequest,
  ): GoalRunnerIterationResult {
    val blocked = state.manifest.withBranchSetupBlockedSubtask(subtaskId, reason)
    val saved = manifestStore.save(state.copy(manifest = blocked), request.dbPathOverride)
    request.eventSink.emit(
      GoalRunnerRunEvent.SubtaskStopped(
        issueKey = saved.manifest.issueKey,
        subtaskId = subtaskId,
        reason = GoalRunnerStopReason.BLOCKED.name.lowercase(),
        blockedReason = reason,
        currentStepId = "preplan",
      ),
    )
    return GoalRunnerIterationResult(
      state = saved,
      report = stopped(
        issueKey = saved.manifest.issueKey,
        attempted = emptyList(),
        subtaskId = subtaskId,
        reason = GoalRunnerStopReason.BLOCKED,
        blockedReason = reason,
        workflowId = state.manifest.workflowIdFor(subtaskId),
        lastResumableStep = "preplan",
      ),
    )
  }

  fun blockedOnRecoveryError(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    error: Throwable,
    request: GoalRunnerRunRequest,
  ): GoalRunnerIterationResult {
    val (targetSubtaskId, reason) = when (error) {
      is IncompatibleGoalPlanningPreparationRecoveryError ->
        error.subtaskId to goalPlanningChildImportConflictBlockedReason(
          state.manifest.issueKey,
          error.subtaskId,
          error,
        )
      else -> throw error
    }
    state.manifest.workflowIdFor(targetSubtaskId)?.takeIf(String::isNotBlank)?.let { workflowId ->
      runCatching {
        outcomeStore.markBlocked(
          workflowId = workflowId,
          blockedReason = reason,
          lastResumableStep = "preplan",
          supervisionEvent = null,
          dbPathOverride = request.dbPathOverride,
        )
      }
    }
    return blockedReviewBaselineIteration(state, targetSubtaskId, reason, request)
  }

  fun emitGoalReviewSummaries(
    issueKey: String,
    subtaskId: Int,
    workflowId: String,
    request: GoalRunnerRunRequest,
  ) {
    outcomeStore.unemittedGoalReviewPasses(workflowId, request.dbPathOverride).forEach { pass ->
      request.eventSink.emit(
        GoalRunnerRunEvent.SubtaskReviewSummary(
          issueKey = issueKey,
          subtaskId = subtaskId,
          passNumber = pass.passNumber,
          verdict = pass.verdict.wireValue,
          findingCount = pass.findings.size,
          unresolvedFindingCount = pass.unresolvedFindingCount,
          findings = pass.findings,
        ),
      )
      check(outcomeStore.acknowledgeGoalReviewPass(workflowId, pass.passNumber, request.dbPathOverride)) {
        "Goal-subtask review summary pass ${pass.passNumber} could not be acknowledged after emission."
      }
    }
  }

  fun prepareAttemptedLaunch(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
    reviewBaseline: GoalSubtaskReviewBaseline,
    planning: GoalPlanningSweepOutcome.PreparedAll,
  ): PreparedLaunch {
    val priorWorkflowId = state.manifest.workflowIdFor(subtaskId)
    val subtask = requireNotNull(state.manifest.subtasks.firstOrNull { it.id == subtaskId }) {
      "Goal subtask '$subtaskId' is missing from the decomposition manifest."
    }
    if (subtask.status == "blocked" && priorWorkflowId != null) {
      reopenBlockedChildForOperatorResume(subtaskId, priorWorkflowId, subtask, request)
    }
    val firstRun = priorWorkflowId == null
    val assignedWorkflowId = priorWorkflowId ?: generateWorkflowId(RUNTIME_WORKFLOW_ID_PREFIX)
    val rawSpecPath = requireNotNull(
      subtask.specPath.takeIf(String::isNotBlank),
    ) { "Goal subtask '$subtaskId' has no governed spec path." }
    val canonicalRepository = runCatching { request.repoRoot.toRealPath() }
      .getOrElse { request.repoRoot.toAbsolutePath().normalize() }
    val lexicalSpecPath = Path.of(rawSpecPath).let { path ->
      (if (path.isAbsolute) path else canonicalRepository.resolve(path)).toAbsolutePath().normalize()
    }
    val resolvedSpecPath = runCatching { lexicalSpecPath.toRealPath() }.getOrElse { lexicalSpecPath }
    check(resolvedSpecPath.startsWith(canonicalRepository)) {
      "Goal subtask '$subtaskId' governed spec path escapes repository '$canonicalRepository'."
    }
    val governedSpecPath = canonicalRepository.relativize(resolvedSpecPath).joinToString("/")
    val attemptedManifest = state.manifest.withAttemptedSubtask(subtaskId)
      .let { manifest -> if (firstRun) manifest.withWorkflowId(subtaskId, assignedWorkflowId) else manifest }
    val attemptedState = run {
      val branch = attemptedManifest.branchPlanFor(subtaskId).branch.takeIf(String::isNotBlank)
        ?: attemptedManifest.featureBranch?.takeIf(String::isNotBlank)
        ?: error("Goal subtask '$subtaskId' has no durable branch for review baseline persistence.")
      manifestStore.saveNewChildWorkflow(
        state.copy(manifest = attemptedManifest),
        GoalRunnerChildWorkflowSetup(
          subtaskId = subtaskId,
          workflowId = assignedWorkflowId,
          goalBranch = branch,
          normalizedIssueKey = state.manifest.issueKey.trim().uppercase(),
          repositoryIdentity = "repo-root-realpath-v1:$canonicalRepository",
          governedSpecPath = governedSpecPath,
          reviewBaseline = reviewBaseline,
          reviewPolicy = GoalRunnerReviewPolicy(
            codeReviewMode = request.codeReviewMode ?: CodeReviewExecutionMode.DEFAULT,
            agentAddonSelection = manifestStore.effectiveAgentAddonSelection(state.parentWorkflowId, request),
          ),
          planningHydration = planning.hydrationFor(subtaskId),
        ),
        request.dbPathOverride,
      )
    }
    return PreparedLaunch(attemptedState, assignedWorkflowId.takeIf { firstRun })
  }

  fun goalBranchSetupFailure(
    state: GoalRunnerManifestState,
    selection: GoalRunnerSelection.Run,
    request: GoalRunnerRunRequest,
  ): GoalRunnerIterationResult? {
    val subtaskId = selection.decision.subtask.id
    val branchPlan = state.manifest.branchPlanFor(subtaskId)
    if (branchPlan.branch.isBlank()) {
      return null
    }
    val checkout = gitOperations.checkoutBranch(request.repoRoot, branchPlan.branch, branchPlan.baseBranch)
    val setupError = if (!checkout.ok) {
      checkout.error
    } else if (branchPlan.validateBase) {
      gitOperations.validateBranchBase(request.repoRoot, branchPlan.branch, branchPlan.baseBranch)
        .takeUnless { it.ok }
        ?.error
        .orEmpty()
    } else {
      ""
    }
    return setupError.takeIf(String::isNotBlank)?.let { error ->
      blockedBranchSetupIteration(state, subtaskId, error, request)
    }
  }

  private fun reopenBlockedChildForOperatorResume(
    subtaskId: Int,
    workflowId: String,
    subtask: DecompositionSubtask,
    request: GoalRunnerRunRequest,
  ) {
    val phaseId = subtask.lastResumableStep?.takeIf(String::isNotBlank)
      ?: FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
    check(
      outcomeStore.reopenBlockedPhaseForOperatorResume(
        workflowId = workflowId,
        preferredPhaseId = phaseId,
        reason = "Operator resumed the goal after a blocked stop at subtask $subtaskId.",
        dbPathOverride = request.dbPathOverride,
      ),
    ) {
      "Goal subtask '$subtaskId' is blocked but child workflow '$workflowId' could not be reopened for resume."
    }
  }

  private fun blockedBranchSetupIteration(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    reason: String,
    request: GoalRunnerRunRequest,
  ): GoalRunnerIterationResult {
    val blocked = state.manifest.withBranchSetupBlockedSubtask(subtaskId, reason)
    val saved = manifestStore.save(state.copy(manifest = blocked), request.dbPathOverride)
    request.eventSink.emit(
      GoalRunnerRunEvent.SubtaskStopped(
        issueKey = saved.manifest.issueKey,
        subtaskId = subtaskId,
        reason = GoalRunnerStopReason.BLOCKED.name.lowercase(),
        blockedReason = reason,
        currentStepId = "create_branch",
      ),
    )
    return GoalRunnerIterationResult(
      state = saved,
      report = stopped(
        issueKey = saved.manifest.issueKey,
        attempted = emptyList(),
        subtaskId = subtaskId,
        reason = GoalRunnerStopReason.BLOCKED,
        blockedReason = reason,
        workflowId = null,
        lastResumableStep = "create_branch",
      ),
    )
  }
}

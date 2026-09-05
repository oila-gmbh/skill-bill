package skillbill.application.goalrunner

import skillbill.application.goalrunner.model.GoalChildOrphanReplacementRequest
import skillbill.application.goalrunner.model.GoalChildOrphanReplacementResult
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.goalrunner.model.GoalRunnerSubtaskLaunchBoundariesPort
import skillbill.application.goalrunner.model.PortableReviewBaselineWriteRequest
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.ports.goalrunner.persistence.PortableReviewBaselinePersistence
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerOrphanChildReplacementWrite
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.gitops.captureGoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
import skillbill.review.context.model.CodeReviewExecutionMode

internal class GoalRunnerCrossMachineChildRecovery(
  private val launchBoundaries: GoalRunnerSubtaskLaunchBoundariesPort,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val portableReviewBaselinePersistence: PortableReviewBaselinePersistence,
) {
  private val manifestStore get() = launchBoundaries.manifestStore
  private val outcomeStore get() = launchBoundaries.outcomeStore
  private val gitOperations get() = launchBoundaries.gitOperations

  fun isCandidate(state: GoalRunnerManifestState, subtaskId: Int, request: GoalRunnerRunRequest): Boolean {
    val workflowId = state.manifest.workflowIdFor(subtaskId) ?: return false
    val subtask = state.manifest.subtasks.single { it.id == subtaskId }
    return !outcomeStore.workflowExists(workflowId, request.dbPathOverride) &&
      PortableReviewBaselineValidator.isSafePreImplementationRecovery(subtask)
  }

  fun captureBaseline(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalSubtaskReviewBaselineResult {
    val branch = state.manifest.branchPlanFor(subtaskId).branch.takeIf(String::isNotBlank)
      ?: state.manifest.featureBranch?.takeIf(String::isNotBlank)
      ?: return GoalSubtaskReviewBaselineResult(
        status = "error",
        error = "Goal subtask '$subtaskId' has no durable child branch for review baseline capture.",
      )
    return gitOperations.captureGoalSubtaskReviewBaseline(request.repoRoot, branch)
  }

  fun persist(args: PersistArgs): PreparedLaunch {
    val replacementRequest = GoalChildOrphanReplacementRequest(
      state = args.state,
      subtaskId = args.subtaskId,
      repoRoot = args.request.repoRoot,
      repositoryIdentity = repositoryEnclosingRootPort.repositoryIdentity(
        repositoryEnclosingRootPort.canonicalPath(args.request.repoRoot),
      ),
      gitOperations = gitOperations,
      codeReviewMode = args.request.codeReviewMode,
      reviewBaseline = args.reviewBaseline,
    )
    val replacement = GoalChildOrphanReplacement.replaceOrphan(replacementRequest)
    val replaced = checkNotNull(replacement as? GoalChildOrphanReplacementResult.Replaced)
    check(replaced.sourceWorkflowId == args.sourceWorkflowId)
    val reviewPolicy = GoalRunnerReviewPolicy(
      codeReviewMode = args.request.codeReviewMode ?: CodeReviewExecutionMode.DEFAULT,
      agentAddonSelection = manifestStore.effectiveAgentAddonSelection(
        args.state.parentWorkflowId,
        args.request,
      ),
    )
    val setup = GoalChildOrphanReplacement.childWorkflowSetup(
      request = replacementRequest,
      replacement = replaced,
      governedSpecPath = args.governedSpecPath,
      reviewPolicy = reviewPolicy,
      planningHydration = args.planning.hydrationFor(args.subtaskId),
    )
    PortableReviewBaselineWriter(portableReviewBaselinePersistence).persistBeforeImplementation(
      PortableReviewBaselineWriteRequest(
        repoRoot = args.request.repoRoot,
        manifest = replaced.state.manifest,
        subtaskId = args.subtaskId,
        workflowId = replaced.replacementWorkflowId,
        repositoryIdentity = replacementRequest.repositoryIdentity,
        goalBranch = setup.goalBranch,
        reviewBaseline = args.reviewBaseline,
      ),
    )
    val saved = manifestStore.replaceOrphanChildWorkflow(
      GoalRunnerOrphanChildReplacementWrite(
        state = replaced.state,
        subtaskId = args.subtaskId,
        sourceWorkflowId = args.sourceWorkflowId,
        setup = setup,
        auditEntry = replaced.auditEntry.copy(recoveryReason = "cross_machine_rebootstrap"),
        dbPathOverride = args.request.dbPathOverride,
      ),
    )
    return PreparedLaunch(saved, replaced.replacementWorkflowId)
  }

  data class PersistArgs(
    val state: GoalRunnerManifestState,
    val subtaskId: Int,
    val request: GoalRunnerRunRequest,
    val reviewBaseline: GoalSubtaskReviewBaseline,
    val planning: GoalPlanningSweepOutcome.PreparedAll,
    val governedSpecPath: String,
    val sourceWorkflowId: String,
  )
}

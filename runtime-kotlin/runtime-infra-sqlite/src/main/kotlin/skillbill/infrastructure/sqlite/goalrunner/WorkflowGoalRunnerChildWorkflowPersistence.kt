package skillbill.infrastructure.sqlite.goalrunner
import skillbill.contracts.issuekey.normalizeRequiredIssueKey
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.goalrunner.GoalRunnerQualityGateSelectionResolver
import skillbill.ports.continuation.FeatureTaskExecutionIdentityPolicy
import skillbill.ports.goalrunner.persistence.GoalChildPlanningHydratorPort
import skillbill.ports.goalrunner.persistence.GoalParentProjectionWriter
import skillbill.ports.goalrunner.persistence.migrateLegacyGoalRunnerControls
import skillbill.ports.goalrunner.runner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.decomposition.runtime.decodeArtifacts
import skillbill.ports.workflow.model.FeatureTaskExecutionIdentity
import skillbill.ports.workflow.model.FeatureTaskRouteScope
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.ports.workflow.persistence.decompositionRuntime
import skillbill.ports.workflow.persistence.findDecomposedParentWorkflow
import skillbill.ports.workflow.persistence.model.WorkflowFamily
import skillbill.ports.workflow.persistence.requireRuntimeModeForEngineWrite
import skillbill.ports.workflow.persistence.toRecord
import skillbill.ports.workflow.persistence.toSnapshot
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import java.nio.file.Path

internal data class SavedGoalChildWorkflow(
  val state: GoalRunnerManifestState,
  val projectionArtifactsJson: String,
)

internal class WorkflowGoalRunnerChildWorkflowPersistence(
  private val engine: WorkflowEngine,
  private val planningHydrator: GoalChildPlanningHydratorPort,
  private val parentProjection: GoalParentProjectionWriter,
  private val decompositionManifestValidator: DecompositionManifestValidator,
) {
  fun saveInTransaction(
    unitOfWork: UnitOfWork,
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
  ): SavedGoalChildWorkflow {
    requireConsistentChildSetup(state, setup)
    val expectedIdentity = expectedChildIdentity(setup)
    val parentUpdated = updateParentForChildWorkflow(unitOfWork, state)
    val existingChild = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, setup.workflowId)
    if (existingChild != null) {
      val persistedIdentity = unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(setup.workflowId)
      if (persistedIdentity != expectedIdentity) {
        throw IncompatibleGoalPlanningPreparationRecoveryError(
          state.parentWorkflowId,
          setup.subtaskId,
          "existing child execution identity conflicts with goal-child setup",
        )
      }
      requireMatchingGoalContinuation(existingChild, state, setup)
      planningHydrator.requireMatchingImport(unitOfWork, existingChild, setup)
    }
    val childUpdated = if (existingChild == null) {
      openGoalChildWorkflow(unitOfWork, state, setup, parentUpdated.workflowId)
    } else {
      existingChild
    }
    if (existingChild == null) {
      WorkflowFamily.TASK_RUNTIME.saveRecord(
        unitOfWork.workflowStates,
        childUpdated.toRecord().copy(issueKey = normalizeRequiredIssueKey(state.manifest.issueKey)),
      )
      val identity = expectedIdentity
      FeatureTaskExecutionIdentityPolicy.validate(identity)
      unitOfWork.workflowStates.saveFeatureTaskExecutionIdentity(identity)
    }
    val refreshedParent =
      WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, parentUpdated.workflowId) ?: parentUpdated
    return SavedGoalChildWorkflow(
      state = GoalRunnerManifestState(
        parentWorkflowId = refreshedParent.workflowId,
        dbPath = unitOfWork.dbPath.toString(),
        manifest = refreshedParent.decompositionRuntime(decompositionManifestValidator) ?: state.manifest,
        controlState = unitOfWork.goalRunnerControls.controlState(refreshedParent.workflowId),
      ),
      projectionArtifactsJson = refreshedParent.artifactsJson,
    )
  }

  private fun expectedChildIdentity(setup: GoalRunnerChildWorkflowSetup) = FeatureTaskExecutionIdentity(
    workflowId = setup.workflowId,
    normalizedIssueKey = setup.normalizedIssueKey,
    repositoryIdentity = setup.repositoryIdentity,
    governedSpecPath = setup.governedSpecPath,
    mode = FeatureTaskWorkflowMode.RUNTIME,
    routeScope = FeatureTaskRouteScope.GOAL_CHILD,
  )

  private fun requireConsistentChildSetup(state: GoalRunnerManifestState, setup: GoalRunnerChildWorkflowSetup) {
    val request = setup.planningHydration ?: return
    val selected = state.manifest.subtasks.singleOrNull { it.id == setup.subtaskId }
    val failures = listOfNotNull(
      "parent workflow".takeIf { request.identity.parentGoalWorkflowId != state.parentWorkflowId },
      "issue key".takeIf {
        request.identity.normalizedIssueKey != setup.normalizedIssueKey ||
          setup.normalizedIssueKey != normalizeRequiredIssueKey(state.manifest.issueKey)
      },
      "repository".takeIf { request.identity.repositoryIdentity != setup.repositoryIdentity },
      "subtask".takeIf { request.descriptor.subtaskId != setup.subtaskId },
      "governed spec".takeIf { request.descriptor.governedSubSpecPath != setup.governedSpecPath },
      "manifest subtask".takeIf {
        selected == null ||
          canonicalGovernedSpecPath(selected.specPath, setup.repositoryIdentity) != setup.governedSpecPath
      },
    )
    if (failures.isNotEmpty()) {
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        state.parentWorkflowId,
        setup.subtaskId,
        "hydration ${failures.joinToString()} does not match child setup",
      )
    }
  }

  private fun canonicalGovernedSpecPath(specPath: String, repositoryIdentity: String): String {
    val repository = Path.of(repositoryIdentity.removePrefix("repo-root-realpath-v1:"))
    val lexical = Path.of(specPath).let { if (it.isAbsolute) it else repository.resolve(it) }
      .toAbsolutePath().normalize()
    val resolved = runCatching { lexical.toRealPath() }.getOrElse { lexical }
    return runCatching { repository.relativize(resolved).joinToString("/") }.getOrElse { specPath }
  }

  private fun requireMatchingGoalContinuation(
    existing: WorkflowStateSnapshot,
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
  ) {
    val continuation = decodeArtifacts(existing.artifactsJson)[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY]
      as? Map<*, *>
    val matches = continuation?.get("issue_key") == state.manifest.issueKey &&
      (continuation["subtask_id"] as? Number)?.toInt() == setup.subtaskId &&
      continuation["parent_workflow_id"] == state.parentWorkflowId &&
      continuation["goal_branch"] == setup.goalBranch && continuation["suppress_pr"] == true
    if (!matches) {
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        state.parentWorkflowId,
        setup.subtaskId,
        "existing child goal continuation conflicts with child setup",
      )
    }
  }

  private fun updateParentForChildWorkflow(
    unitOfWork: UnitOfWork,
    state: GoalRunnerManifestState,
  ): WorkflowStateSnapshot {
    val existingRecord = unitOfWork.workflowStates.getFeatureTaskWorkflow(state.parentWorkflowId)
      ?: unitOfWork.workflowStates.findDecomposedParentWorkflow(
        state.manifest.issueKey,
        decompositionManifestValidator,
      )
      ?: error("Unknown decomposed parent workflow '${state.parentWorkflowId}'.")
    existingRecord.requireRuntimeModeForEngineWrite()
    val existingParent = existingRecord.toSnapshot()
    migrateLegacyGoalRunnerControls(unitOfWork, existingParent)
    val parentUpdated = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      existingParent,
      WorkflowUpdateInput(
        workflowStatus = existingParent.workflowStatus,
        currentStepId = existingParent.currentStepId,
        stepUpdates = null,
        artifactsPatch = parentProjection.artifacts(
          mergeConcurrentGoalProgress(
            existingParent.decompositionRuntime(decompositionManifestValidator) ?: state.manifest,
            state.manifest,
          ),
          existingParent.artifactsJson,
        ),
        sessionId = existingParent.sessionId.orEmpty(),
        replaceArtifacts = true,
      ),
    )
    WorkflowFamily.TASK_RUNTIME.saveRecord(
      unitOfWork.workflowStates,
      parentUpdated.toRecord().copy(issueKey = normalizeRequiredIssueKey(state.manifest.issueKey)),
    )
    return parentUpdated
  }

  private fun openGoalChildWorkflow(
    unitOfWork: UnitOfWork,
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
    parentWorkflowId: String,
  ): WorkflowStateSnapshot {
    val openedChild = engine.openRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      setup.workflowId,
      "${WorkflowFamily.TASK_RUNTIME.definition.defaultSessionPrefix}-${state.manifest.issueKey}",
      WorkflowFamily.TASK_RUNTIME.definition.defaultInitialStepId,
    )
    val hydration = planningHydrator.hydrate(
      unitOfWork,
      setup,
      requireNotNull(setup.planningHydration) {
        "Prepared goal child '${setup.subtaskId}' requires planning hydration."
      },
    )
    return engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      openedChild,
      WorkflowUpdateInput(
        workflowStatus = openedChild.workflowStatus,
        currentStepId = hydration.currentStepId,
        stepUpdates = hydration.stepUpdates,
        artifactsPatch = childWorkflowArtifacts(state, setup, parentWorkflowId) + hydration.artifacts,
        sessionId = openedChild.sessionId.orEmpty(),
      ),
    )
  }

  private fun childWorkflowArtifacts(
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
    parentWorkflowId: String,
  ): Map<String, Any?> = mapOf(
    FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to FeatureTaskRuntimeGoalContinuationArtifact(
      issueKey = state.manifest.issueKey,
      subtaskId = setup.subtaskId,
      suppressPr = true,
      goalBranch = setup.goalBranch,
      parentWorkflowId = parentWorkflowId,
      codeReviewMode = setup.reviewPolicy.codeReviewMode,
      validationDepth = ValidationDepth.FULL,
      qualityGateSelection = GoalRunnerQualityGateSelectionResolver.resolve(state.manifest, setup.subtaskId),
      subtaskName = state.manifest.subtasks.firstOrNull { it.id == setup.subtaskId }?.name?.takeIf(String::isNotBlank),
    ).toArtifactMap(),
    GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to GoalSubtaskReviewState.initial(
      reviewBaseSha = setup.reviewBaseline.reviewBaseSha,
      baselineUntrackedPaths = setup.reviewBaseline.baselineUntrackedPaths,
      codeReviewMode = setup.reviewPolicy.codeReviewMode,
    ).toArtifactMap(),
    "install_sync_result" to mapOf(
      "status" to "deferred",
      "reason" to
        "goal-continuation defers installer, uninstall, and install-sync flows until the parent goal exits; " +
        "deferred install sync must not block subtask completion",
    ),
  )
}

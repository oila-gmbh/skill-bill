package skillbill.application.work

import me.tatarka.inject.annotations.Inject
import skillbill.application.goalrunner.goalRepositoryIdentity
import skillbill.application.model.IdeStatusRequest
import skillbill.application.model.IdeStatusResult
import skillbill.application.model.IdeStatusSnapshot
import skillbill.application.model.IdeStatusWorkflowFamily
import skillbill.application.workflow.WorkflowFamily
import skillbill.error.InvalidWorkListRowError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.model.FeatureTaskRouteScope
import skillbill.ports.persistence.model.WorkItem
import skillbill.ports.persistence.model.WorkItemKind
import skillbill.workflow.IdeStatusValidator
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

/**
 * SKILL-148 Subtask 1: read-only application projection for IDE status.
 *
 * Side-effect free beyond process-local reads: no workflow transition, manifest
 * rewrite, lease acquisition, telemetry mutation, or database write.
 */
@Inject
class IdeStatusService(
  private val database: DatabaseSessionFactory,
  private val projector: IdeStatusProjector,
  private val ideStatusValidator: IdeStatusValidator,
  private val clock: Clock = Clock.systemUTC(),
) {

  fun status(request: IdeStatusRequest): IdeStatusResult {
    val observedAt = request.observedAt ?: Instant.now(clock)
    val identityResult = resolveRepositoryIdentity(request.repoRoot)
    if (identityResult is IdentityResolution.Invalid) {
      return emit(IdeStatusProblemSnapshots.invalidRepositoryInput(observedAt, identityResult.message))
    }
    if (identityResult is IdentityResolution.Missing) {
      return emit(IdeStatusProblemSnapshots.missingRepositoryIdentity(observedAt, identityResult.message))
    }
    val repositoryIdentity = (identityResult as IdentityResolution.Ok).identity
    val repoRoot = identityResult.repoRoot

    if (!database.databaseExists(request.dbOverride)) {
      return emit(IdeStatusProblemSnapshots.absentDatabase(repositoryIdentity, observedAt))
    }

    return try {
      database.read(request.dbOverride) { unitOfWork ->
        val candidates = collectCandidates(unitOfWork, repositoryIdentity)
        val selected = IdeStatusSelectionPolicy.select(candidates)
          ?: return@read emit(IdeStatusProblemSnapshots.noMatchingWork(repositoryIdentity, observedAt))
        val snapshot = projector.project(
          candidate = selected,
          unitOfWork = unitOfWork,
          repositoryIdentity = repositoryIdentity,
          observedAt = observedAt,
          dbOverride = request.dbOverride,
          repoRoot = repoRoot,
        )
        emit(snapshot)
      }
    } catch (error: InvalidWorkListRowError) {
      emit(
        IdeStatusProblemSnapshots.incompatibleRecord(
          repositoryIdentity = repositoryIdentity,
          observedAt = observedAt,
          message = error.message ?: "Incompatible work-list record.",
        ),
      )
    } catch (error: InvalidWorkflowStateSchemaError) {
      emit(
        IdeStatusProblemSnapshots.incompatibleRecord(
          repositoryIdentity = repositoryIdentity,
          observedAt = observedAt,
          message = error.message ?: "Incompatible workflow record.",
        ),
      )
    }
  }

  private fun collectCandidates(
    unitOfWork: UnitOfWork,
    repositoryIdentity: String,
  ): List<IdeStatusCandidate> {
    val work = unitOfWork.workList.list(limit = null)
    val issueKeysWithGoals = work
      .filter { it.workflowKind == WorkItemKind.FEATURE_GOAL }
      .mapNotNull { it.issueKey?.uppercase() }
      .toSet()

    return work.mapNotNull { item ->
      toCandidate(unitOfWork, item, repositoryIdentity, issueKeysWithGoals)
    }
  }

  private fun toCandidate(
    unitOfWork: UnitOfWork,
    item: WorkItem,
    repositoryIdentity: String,
    issueKeysWithGoals: Set<String>,
  ): IdeStatusCandidate? {
    val family = item.workflowKind.toIdeFamily()
    val identityMatch = matchesRepository(unitOfWork, item, family, repositoryIdentity) ?: return null
    if (!identityMatch) return null

    val lifecycle = IdeStatusSelectionPolicy.lifecycleFromDurableState(item.currentState) ?: return null
    val routeScope = when (item.workflowKind) {
      WorkItemKind.FEATURE_TASK_PROSE, WorkItemKind.FEATURE_TASK_RUNTIME ->
        unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(item.workflowId)?.routeScope
      else -> null
    }
    // Within a tier, never prefer a goal-child over an authoritative feature-goal for the same issue.
    val suppressedChild = routeScope == FeatureTaskRouteScope.GOAL_CHILD &&
      item.issueKey?.uppercase() in issueKeysWithGoals
    if (suppressedChild) return null

    val updatedAt = authoritativeUpdatedAt(unitOfWork, item, family) ?: item.stateEnteredAt
    val startedAt = item.startedAt


    return IdeStatusCandidate(
      workflowId = item.workflowId,
      workflowFamily = family,
      issueKey = item.issueKey,
      currentState = item.currentState,
      lifecycleState = lifecycle,
      selectionTier = IdeStatusSelectionPolicy.selectionTier(lifecycle),
      updatedAt = updatedAt,
      startedAt = startedAt,
      routeScope = routeScope,
      isGoalAuthoritative = family == IdeStatusWorkflowFamily.FEATURE_GOAL,
    )
  }

  /**
   * Returns true when the item matches [repositoryIdentity], false when it belongs to another
   * repository, and null when identity cannot be resolved (exclude from selection).
   */
  private fun matchesRepository(
    unitOfWork: UnitOfWork,
    item: WorkItem,
    family: IdeStatusWorkflowFamily,
    repositoryIdentity: String,
  ): Boolean? = when (family) {
    IdeStatusWorkflowFamily.FEATURE_TASK_PROSE,
    IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME,
    -> {
      val identity = unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(item.workflowId)
      when {
        identity == null -> null
        identity.repositoryIdentity == repositoryIdentity -> true
        else -> false
      }
    }
    IdeStatusWorkflowFamily.FEATURE_GOAL -> {
      val bound = unitOfWork.goalRunnerControls.controlState(item.workflowId).repositoryIdentity
      when {
        bound == null -> {
          // Infer from children: children in this repository bind the goal here; a goal with no
          // children anywhere is treated as belonging to the asking repository (unlaunched).
          val issueKey = item.issueKey?.trim()?.uppercase() ?: return null
          val childrenHere = unitOfWork.workflowStates
            .findGoalChildFeatureTaskCandidates(issueKey, repositoryIdentity)
          val childCountAnywhere = unitOfWork.workflowStates.countGoalChildIdentities(issueKey)
          childrenHere.isNotEmpty() || childCountAnywhere == 0
        }
        bound == repositoryIdentity -> true
        else -> false
      }
    }
    IdeStatusWorkflowFamily.FEATURE_VERIFY -> {
      // Verify workflows have no durable repository_identity column. Include them for the
      // queried root so AC-006 projection remains available; repository isolation for verify
      // is not enforceable until verify gains an execution-identity binding.
      true
    }
  }

  private fun authoritativeUpdatedAt(
    unitOfWork: UnitOfWork,
    item: WorkItem,
    family: IdeStatusWorkflowFamily,
  ): Instant? {
    val snapshot = when (family) {
      IdeStatusWorkflowFamily.FEATURE_TASK_PROSE ->
        WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, item.workflowId)
      IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME ->
        WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, item.workflowId)
      IdeStatusWorkflowFamily.FEATURE_VERIFY ->
        WorkflowFamily.VERIFY.get(unitOfWork.workflowStates, item.workflowId)
      IdeStatusWorkflowFamily.FEATURE_GOAL -> null
    }
    return parseInstantOrNull(snapshot?.updatedAt) ?: item.stateEnteredAt
  }

  private fun emit(snapshot: IdeStatusSnapshot): IdeStatusResult {
    val wire = snapshot.toWireMap()
    ideStatusValidator.validate(wire, sourceLabel = "ide-status")
    return IdeStatusResult(snapshot = snapshot, exitCode = snapshot.exitCode())
  }
}

private sealed class IdentityResolution {
  data class Ok(val identity: String, val repoRoot: Path) : IdentityResolution()
  data class Invalid(val message: String) : IdentityResolution()
  data class Missing(val message: String) : IdentityResolution()
}

private fun resolveRepositoryIdentity(repoRootArg: String): IdentityResolution {
  val raw = Path.of(repoRootArg)
  if (!Files.exists(raw)) {
    return IdentityResolution.Invalid("Repository root does not exist: $repoRootArg")
  }
  if (!Files.isDirectory(raw)) {
    return IdentityResolution.Invalid("Repository root is not a directory: $repoRootArg")
  }
  val resolvedStart = runCatching { raw.toAbsolutePath().normalize().toRealPath() }
    .getOrElse {
      return IdentityResolution.Invalid("Repository root cannot be resolved: $repoRootArg")
    }
  var candidate = resolvedStart
  var foundGit = false
  while (true) {
    if (Files.exists(candidate.resolve(".git"))) {
      foundGit = true
      break
    }
    candidate = candidate.parent ?: break
  }
  if (!foundGit) {
    return IdentityResolution.Invalid("Path is not inside a Git repository: $repoRootArg")
  }
  val gitRoot = runCatching { candidate.toRealPath() }
    .getOrElse {
      return IdentityResolution.Invalid("Git repository root cannot be resolved: $repoRootArg")
    }
  val identity = goalRepositoryIdentity(gitRoot)
  if (identity.isBlank() || !identity.startsWith("repo-root-realpath-v1:")) {
    return IdentityResolution.Missing("Could not form canonical repository identity for: $repoRootArg")
  }
  return IdentityResolution.Ok(identity = identity, repoRoot = gitRoot)
}

package skillbill.application.work

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskRuntimeBranchSetup
import skillbill.application.goalrunner.goalRepositoryIdentity
import skillbill.application.idestatus.model.IdeStatusCandidate
import skillbill.application.idestatus.model.IdeStatusRepositoryResolution
import skillbill.application.idestatus.model.IdeStatusRequest
import skillbill.application.idestatus.model.IdeStatusResult
import skillbill.application.idestatus.model.IdeStatusSnapshot
import skillbill.application.idestatus.model.IdeStatusWorkflowFamily
import skillbill.error.InvalidWorkListRowError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.IdeStatusValidator
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkItemKind
import skillbill.ports.workflow.model.FeatureTaskRouteScope
import java.nio.file.Path
import java.time.Clock

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
  private val branchSource: CheckedOutBranchSource,
  private val clock: Clock,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
) {

  fun status(request: IdeStatusRequest): IdeStatusResult {
    val observedAt = request.observedAt ?: clock.instant()
    val identityResult = resolveRepositoryIdentity(request.repoRoot, repositoryEnclosingRootPort)
    if (identityResult is IdeStatusRepositoryResolution.Invalid) {
      return emit(IdeStatusProblemSnapshots.invalidRepositoryInput(observedAt, identityResult.message))
    }
    if (identityResult is IdeStatusRepositoryResolution.Missing) {
      return emit(IdeStatusProblemSnapshots.missingRepositoryIdentity(observedAt, identityResult.message))
    }
    val repositoryIdentity = (identityResult as IdeStatusRepositoryResolution.Ok).identity
    val repoRoot = identityResult.repoRoot

    if (!database.databaseExists(request.dbOverride)) {
      return emit(IdeStatusProblemSnapshots.absentDatabase(repositoryIdentity, observedAt))
    }

    val currentBranch = branchSource.checkedOutBranch(repoRoot)
    return try {
      database.read(request.dbOverride) { unitOfWork ->
        val candidates = scopeToBranch(collectCandidates(unitOfWork, repositoryIdentity), currentBranch)
        val selected = IdeStatusSelectionPolicy.select(candidates, observedAt)
          ?: return@read emit(IdeStatusProblemSnapshots.noMatchingWork(repositoryIdentity, observedAt, currentBranch))
        val snapshot = projector.project(
          candidate = selected,
          context = IdeStatusProjectionContext(
            unitOfWork = unitOfWork,
            repositoryIdentity = repositoryIdentity,
            observedAt = observedAt,
            dbOverride = request.dbOverride,
            repoRoot = repoRoot,
          ),
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

  /**
   * The widget answers "what is happening on the branch I am looking at", so candidates
   * whose issue key does not appear in the checked-out branch name are dropped. With no
   * resolvable branch (detached HEAD, rebase) scoping is disabled instead of hiding work.
   *
   * A protected base branch (`main`/`master`/`trunk`) also disables scoping. Work only
   * acquires its issue-named branch at the `create_branch` step, which for a goal runs at
   * the first subtask launch — so between goal start and that step the run sits on the base
   * branch and name-matching would hide the very work the surface exists to report. A base
   * branch is not a feature context to scope to, so the answer there is repository-wide.
   */
  private fun scopeToBranch(candidates: List<IdeStatusCandidate>, branch: String?): List<IdeStatusCandidate> {
    if (branch == null) return candidates
    if (FeatureTaskRuntimeBranchSetup.protectedBranchName(branch) != null) return candidates
    return candidates.filter { candidate ->
      candidate.issueKey?.let { IdeStatusBranchScope.branchReferencesIssueKey(branch, it) } == true
    }
  }

  private fun collectCandidates(unitOfWork: UnitOfWork, repositoryIdentity: String): List<IdeStatusCandidate> {
    val work = unitOfWork.workList.list(limit = null)
    val issueKeysWithGoals = work
      .filter { it.workflowKind == WorkItemKind.FEATURE_GOAL }
      .mapNotNull { it.issueKey?.uppercase() }
      .toSet()
    val repositoryCorrelation = IdeStatusRepositoryCorrelation(unitOfWork, repositoryIdentity)
    val livenessAnchors = IdeStatusLivenessAnchors(unitOfWork, repositoryIdentity)

    return work.mapNotNull { item ->
      toCandidate(item, issueKeysWithGoals, repositoryCorrelation, livenessAnchors, unitOfWork)
    }
  }

  private fun toCandidate(
    item: WorkItem,
    issueKeysWithGoals: Set<String>,
    repositoryCorrelation: IdeStatusRepositoryCorrelation,
    livenessAnchors: IdeStatusLivenessAnchors,
    unitOfWork: UnitOfWork,
  ): IdeStatusCandidate? {
    val family = item.workflowKind.toIdeFamily()
    val lifecycle = family?.let { candidateFamily ->
      if (repositoryCorrelation.matches(item, candidateFamily) != true) {
        null
      } else {
        IdeStatusSelectionPolicy.lifecycleFromDurableState(item.currentState)
      }
    }
    if (family == null || lifecycle == null) return null
    val routeScope = routeScopeFor(item, unitOfWork)
    if (isExcludedGoalChild(routeScope, item.issueKey, issueKeysWithGoals)) return null
    return IdeStatusCandidate(
      workflowId = item.workflowId,
      workflowFamily = family,
      issueKey = item.issueKey,
      currentState = item.currentState,
      lifecycleState = lifecycle,
      selectionTier = IdeStatusSelectionPolicy.selectionTier(lifecycle),
      updatedAt = livenessAnchors.authoritativeUpdatedAt(item, family) ?: item.stateEnteredAt,
      startedAt = item.startedAt,
      routeScope = routeScope,
      isGoalAuthoritative = family == IdeStatusWorkflowFamily.FEATURE_GOAL,
    )
  }

  private fun routeScopeFor(item: WorkItem, unitOfWork: UnitOfWork): FeatureTaskRouteScope? = when (item.workflowKind) {
    WorkItemKind.FEATURE_TASK_PROSE, WorkItemKind.FEATURE_TASK_RUNTIME ->
      unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(item.workflowId)?.routeScope
    WorkItemKind.FEATURE_VERIFY,
    WorkItemKind.FEATURE_GOAL,
    -> null
  }

  private fun isExcludedGoalChild(
    routeScope: FeatureTaskRouteScope?,
    issueKey: String?,
    issueKeysWithGoals: Set<String>,
  ): Boolean = routeScope == FeatureTaskRouteScope.GOAL_CHILD && issueKey?.uppercase() in issueKeysWithGoals

  private fun emit(snapshot: IdeStatusSnapshot): IdeStatusResult {
    val wire = snapshot.toStatusWireMap()
    ideStatusValidator.validate(wire, sourceLabel = "ide-status")
    return IdeStatusResult(snapshot = snapshot, exitCode = snapshot.exitCode())
  }
}

/**
 * Resolve canonical repository identity without direct file-IO helpers: existence and the
 * `.git` walk use `Path.toRealPath()`, matching `goalRepositoryIdentity`.
 */
internal fun resolveRepositoryIdentity(
  repoRootArg: String,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): IdeStatusRepositoryResolution {
  val resolvedStart = runCatching {
    repositoryEnclosingRootPort.canonicalPath(Path.of(repoRootArg))
  }.getOrNull()
    ?: return IdeStatusRepositoryResolution.Invalid("Repository root cannot be resolved: $repoRootArg")
  val gitRoot = findGitRoot(resolvedStart, repositoryEnclosingRootPort)
    ?: return IdeStatusRepositoryResolution.Invalid("Path is not inside a Git repository: $repoRootArg")
  val canonicalGitRoot = repositoryEnclosingRootPort.canonicalPath(gitRoot)
  val identity = goalRepositoryIdentity(canonicalGitRoot, repositoryEnclosingRootPort)
  return if (identity.isBlank() || !identity.startsWith("repo-root-realpath-v1:")) {
    IdeStatusRepositoryResolution.Missing(
      "Could not form canonical repository identity for: $repoRootArg",
    )
  } else {
    IdeStatusRepositoryResolution.Ok(identity = identity, repoRoot = canonicalGitRoot)
  }
}

private fun findGitRoot(start: Path, repositoryEnclosingRootPort: RepositoryEnclosingRootPort): Path? {
  var candidate: Path? = start
  while (candidate != null) {
    if (repositoryEnclosingRootPort.optionalRealPath(candidate.resolve(".git")) != null) return candidate
    candidate = candidate.parent
  }
  return null
}

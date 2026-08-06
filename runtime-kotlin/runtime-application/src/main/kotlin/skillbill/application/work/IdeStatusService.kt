package skillbill.application.work

import me.tatarka.inject.annotations.Inject
import skillbill.application.goalrunner.goalRepositoryIdentity
import skillbill.application.model.IdeStatusCandidate
import skillbill.application.model.IdeStatusRepositoryResolution
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
      // Verify workflows have no durable repository_identity. Exclude unbound rows (same as a
      // missing feature-task identity). Include only when issue_key correlates to same-repo
      // feature-task or goal identity; never default-include every verify row.
      val issueKey = item.issueKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
      when (verifyIssueRepositoryCorrelation(unitOfWork, issueKey, repositoryIdentity)) {
        VerifyRepoCorrelation.SAME_REPO -> true
        VerifyRepoCorrelation.OTHER_REPO -> false
        VerifyRepoCorrelation.UNKNOWN -> null
      }
    }
  }

  /**
   * Correlate a verify row to a repository via same-issue feature-task/goal durable identity.
   * Positive same-repo evidence includes the verify; other-repo-only evidence excludes it as
   * belonging elsewhere; no correlating work leaves identity unresolved.
   */
  private fun verifyIssueRepositoryCorrelation(
    unitOfWork: UnitOfWork,
    issueKey: String,
    repositoryIdentity: String,
  ): VerifyRepoCorrelation {
    val normalized = issueKey.trim().uppercase()
    var sawSameRepo = false
    var sawOtherRepo = false
    for (other in unitOfWork.workList.list(limit = null)) {
      if (other.issueKey?.trim()?.uppercase() != normalized) continue
      when (other.workflowKind) {
        WorkItemKind.FEATURE_TASK_PROSE,
        WorkItemKind.FEATURE_TASK_RUNTIME,
        -> {
          val identity = unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(other.workflowId)
          when {
            identity == null -> Unit
            identity.repositoryIdentity == repositoryIdentity -> sawSameRepo = true
            else -> sawOtherRepo = true
          }
        }
        WorkItemKind.FEATURE_GOAL -> {
          val bound = unitOfWork.goalRunnerControls.controlState(other.workflowId).repositoryIdentity
          when {
            bound == repositoryIdentity -> sawSameRepo = true
            bound == null -> {
              val childrenHere = unitOfWork.workflowStates
                .findGoalChildFeatureTaskCandidates(normalized, repositoryIdentity)
              val childCountAnywhere = unitOfWork.workflowStates.countGoalChildIdentities(normalized)
              when {
                childrenHere.isNotEmpty() -> sawSameRepo = true
                childCountAnywhere > 0 -> sawOtherRepo = true
                // Unlaunched goal with no children is not positive same-repo evidence for verify.
              }
            }
            else -> sawOtherRepo = true
          }
        }
        WorkItemKind.FEATURE_VERIFY -> Unit
      }
    }
    return when {
      sawSameRepo -> VerifyRepoCorrelation.SAME_REPO
      sawOtherRepo -> VerifyRepoCorrelation.OTHER_REPO
      else -> VerifyRepoCorrelation.UNKNOWN
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

/**
 * Resolve canonical repository identity without direct file-IO helpers: existence and the
 * `.git` walk use `Path.toRealPath()`, matching `goalRepositoryIdentity`.
 */
internal fun resolveRepositoryIdentity(repoRootArg: String): IdeStatusRepositoryResolution {
  val resolvedStart = runCatching { Path.of(repoRootArg).toAbsolutePath().normalize().toRealPath() }
    .getOrElse {
      return IdeStatusRepositoryResolution.Invalid("Repository root cannot be resolved: $repoRootArg")
    }
  var candidate = resolvedStart
  var gitRoot: Path? = null
  while (true) {
    val gitMarker = runCatching { candidate.resolve(".git").toRealPath() }.getOrNull()
    if (gitMarker != null) {
      gitRoot = candidate
      break
    }
    candidate = candidate.parent ?: break
  }
  if (gitRoot == null) {
    return IdeStatusRepositoryResolution.Invalid("Path is not inside a Git repository: $repoRootArg")
  }
  val canonicalGitRoot = runCatching { gitRoot.toRealPath() }
    .getOrElse {
      return IdeStatusRepositoryResolution.Invalid("Git repository root cannot be resolved: $repoRootArg")
    }
  val identity = goalRepositoryIdentity(canonicalGitRoot)
  if (identity.isBlank() || !identity.startsWith("repo-root-realpath-v1:")) {
    return IdeStatusRepositoryResolution.Missing(
      "Could not form canonical repository identity for: $repoRootArg",
    )
  }
  return IdeStatusRepositoryResolution.Ok(identity = identity, repoRoot = canonicalGitRoot)
}

private enum class VerifyRepoCorrelation {
  SAME_REPO,
  OTHER_REPO,
  UNKNOWN,
}

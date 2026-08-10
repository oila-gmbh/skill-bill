package skillbill.application.work

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskRuntimeBranchSetup
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
import skillbill.ports.system.CheckedOutBranchSource
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
@Suppress("TooManyFunctions") // repository-matching helpers stay colocated with selection
class IdeStatusService(
  private val database: DatabaseSessionFactory,
  private val projector: IdeStatusProjector,
  private val ideStatusValidator: IdeStatusValidator,
  private val branchSource: CheckedOutBranchSource,
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
    // SKILL-175: a legacy prose work item has no live IDE status family; it stays listed in the
    // work list (via WorkListService) but is excluded from IDE status projection here.
    val family = item.workflowKind.toIdeFamily()
    if (family == null || matchesRepository(unitOfWork, item, family, repositoryIdentity) != true) return null

    val lifecycle = IdeStatusSelectionPolicy.lifecycleFromDurableState(item.currentState)
      ?: return null
    val routeScope = when (item.workflowKind) {
      WorkItemKind.FEATURE_TASK_PROSE, WorkItemKind.FEATURE_TASK_RUNTIME ->
        unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(item.workflowId)?.routeScope
      else -> null
    }
    // Within a tier, never prefer a goal-child over an authoritative feature-goal for the same issue.
    if (routeScope == FeatureTaskRouteScope.GOAL_CHILD &&
      item.issueKey?.uppercase() in issueKeysWithGoals
    ) {
      return null
    }

    return IdeStatusCandidate(
      workflowId = item.workflowId,
      workflowFamily = family,
      issueKey = item.issueKey,
      currentState = item.currentState,
      lifecycleState = lifecycle,
      selectionTier = IdeStatusSelectionPolicy.selectionTier(lifecycle),
      updatedAt = authoritativeUpdatedAt(unitOfWork, item, family, repositoryIdentity) ?: item.stateEnteredAt,
      startedAt = item.startedAt,
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
    IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME ->
      matchesFeatureTaskRepository(unitOfWork, item.workflowId, repositoryIdentity)
    IdeStatusWorkflowFamily.FEATURE_GOAL ->
      matchesGoalRepository(unitOfWork, item, repositoryIdentity)
    IdeStatusWorkflowFamily.FEATURE_VERIFY ->
      matchesVerifyRepository(unitOfWork, item, repositoryIdentity)
  }

  private fun matchesFeatureTaskRepository(
    unitOfWork: UnitOfWork,
    workflowId: String,
    repositoryIdentity: String,
  ): Boolean? {
    val identity = unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(workflowId) ?: return null
    return identity.repositoryIdentity == repositoryIdentity
  }

  private fun matchesGoalRepository(unitOfWork: UnitOfWork, item: WorkItem, repositoryIdentity: String): Boolean? {
    val bound = unitOfWork.goalRunnerControls.controlState(item.workflowId).repositoryIdentity
    return when {
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

  private fun matchesVerifyRepository(unitOfWork: UnitOfWork, item: WorkItem, repositoryIdentity: String): Boolean? {
    // Verify workflows have no durable repository_identity. Exclude unbound rows (same as a
    // missing feature-task identity). Include only when issue_key correlates to same-repo
    // feature-task or goal identity; never default-include every verify row.
    val issueKey = item.issueKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when (verifyIssueRepositoryCorrelation(unitOfWork, issueKey, repositoryIdentity)) {
      VerifyRepoCorrelation.SAME_REPO -> true
      VerifyRepoCorrelation.OTHER_REPO -> false
      VerifyRepoCorrelation.UNKNOWN -> null
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
      when (correlateSameIssueWork(unitOfWork, other, normalized, repositoryIdentity)) {
        VerifyRepoCorrelation.SAME_REPO -> sawSameRepo = true
        VerifyRepoCorrelation.OTHER_REPO -> sawOtherRepo = true
        VerifyRepoCorrelation.UNKNOWN -> Unit
      }
    }
    return when {
      sawSameRepo -> VerifyRepoCorrelation.SAME_REPO
      sawOtherRepo -> VerifyRepoCorrelation.OTHER_REPO
      else -> VerifyRepoCorrelation.UNKNOWN
    }
  }

  private fun correlateSameIssueWork(
    unitOfWork: UnitOfWork,
    other: WorkItem,
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): VerifyRepoCorrelation = when (other.workflowKind) {
    WorkItemKind.FEATURE_TASK_PROSE,
    WorkItemKind.FEATURE_TASK_RUNTIME,
    -> {
      val identity = unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(other.workflowId)
      when {
        identity == null -> VerifyRepoCorrelation.UNKNOWN
        identity.repositoryIdentity == repositoryIdentity -> VerifyRepoCorrelation.SAME_REPO
        else -> VerifyRepoCorrelation.OTHER_REPO
      }
    }
    WorkItemKind.FEATURE_GOAL -> correlateGoalForVerify(
      unitOfWork = unitOfWork,
      workflowId = other.workflowId,
      normalizedIssueKey = normalizedIssueKey,
      repositoryIdentity = repositoryIdentity,
    )
    WorkItemKind.FEATURE_VERIFY -> VerifyRepoCorrelation.UNKNOWN
  }

  private fun correlateGoalForVerify(
    unitOfWork: UnitOfWork,
    workflowId: String,
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): VerifyRepoCorrelation {
    val bound = unitOfWork.goalRunnerControls.controlState(workflowId).repositoryIdentity
    return when {
      bound == repositoryIdentity -> VerifyRepoCorrelation.SAME_REPO
      bound == null -> {
        val childrenHere = unitOfWork.workflowStates
          .findGoalChildFeatureTaskCandidates(normalizedIssueKey, repositoryIdentity)
        val childCountAnywhere = unitOfWork.workflowStates.countGoalChildIdentities(normalizedIssueKey)
        when {
          childrenHere.isNotEmpty() -> VerifyRepoCorrelation.SAME_REPO
          childCountAnywhere > 0 -> VerifyRepoCorrelation.OTHER_REPO
          // Unlaunched goal with no children is not positive same-repo evidence for verify.
          else -> VerifyRepoCorrelation.UNKNOWN
        }
      }
      else -> VerifyRepoCorrelation.OTHER_REPO
    }
  }

  private fun authoritativeUpdatedAt(
    unitOfWork: UnitOfWork,
    item: WorkItem,
    family: IdeStatusWorkflowFamily,
    repositoryIdentity: String,
  ): Instant? {
    val fromWorkflow = when (family) {
      IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME ->
        WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, item.workflowId)
      IdeStatusWorkflowFamily.FEATURE_VERIFY ->
        WorkflowFamily.VERIFY.get(unitOfWork.workflowStates, item.workflowId)
      // A goal parent carries no workflow snapshot of its own, and its durable
      // `state_entered_at` only advances on a status transition. A goal that stays running
      // therefore ages into staleness while its child task is actively writing, so the newest
      // same-repo child write is the authoritative liveness anchor.
      IdeStatusWorkflowFamily.FEATURE_GOAL -> null
    }?.let { parseInstantOrNull(it.updatedAt) }
      ?: latestGoalChildUpdatedAt(unitOfWork, item, family, repositoryIdentity)

    return listOfNotNull(fromWorkflow, item.stateEnteredAt, goalLeaseHeartbeatAt(unitOfWork, item, family))
      .maxOrNull()
  }

  /**
   * The goal runner heartbeats its execution lease while it holds the goal, so the last
   * heartbeat is the newest authoritative write about a goal that never reached a status
   * transition — and, for a run that was stopped or killed, the closest durable record of
   * when it stopped (accurate to the heartbeat interval). Without it a goal that dies during
   * a long quiet phase reports its start as its last update, and the settled elapsed clock
   * understates the run.
   */
  private fun goalLeaseHeartbeatAt(unitOfWork: UnitOfWork, item: WorkItem, family: IdeStatusWorkflowFamily): Instant? {
    if (family != IdeStatusWorkflowFamily.FEATURE_GOAL) return null
    val lease = unitOfWork.goalRunnerControls.controlState(item.workflowId).executionLease ?: return null
    return parseInstantOrNull(lease.heartbeatAt)
  }

  private fun latestGoalChildUpdatedAt(
    unitOfWork: UnitOfWork,
    item: WorkItem,
    family: IdeStatusWorkflowFamily,
    repositoryIdentity: String,
  ): Instant? {
    if (family != IdeStatusWorkflowFamily.FEATURE_GOAL) return null
    val issueKey = item.issueKey?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
    return unitOfWork.workflowStates
      .findGoalChildFeatureTaskCandidates(issueKey, repositoryIdentity)
      .mapNotNull { parseInstantOrNull(it.workflow.updatedAt) }
      .maxOrNull()
  }

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
internal fun resolveRepositoryIdentity(repoRootArg: String): IdeStatusRepositoryResolution {
  val resolvedStart = runCatching { Path.of(repoRootArg).toAbsolutePath().normalize().toRealPath() }
    .getOrNull()
    ?: return IdeStatusRepositoryResolution.Invalid("Repository root cannot be resolved: $repoRootArg")
  val gitRoot = findGitRoot(resolvedStart)
    ?: return IdeStatusRepositoryResolution.Invalid("Path is not inside a Git repository: $repoRootArg")
  val canonicalGitRoot = runCatching { gitRoot.toRealPath() }.getOrNull()
    ?: return IdeStatusRepositoryResolution.Invalid("Git repository root cannot be resolved: $repoRootArg")
  val identity = goalRepositoryIdentity(canonicalGitRoot)
  return if (identity.isBlank() || !identity.startsWith("repo-root-realpath-v1:")) {
    IdeStatusRepositoryResolution.Missing(
      "Could not form canonical repository identity for: $repoRootArg",
    )
  } else {
    IdeStatusRepositoryResolution.Ok(identity = identity, repoRoot = canonicalGitRoot)
  }
}

private fun findGitRoot(start: Path): Path? {
  var candidate: Path? = start
  while (candidate != null) {
    val gitMarker = runCatching { candidate.resolve(".git").toRealPath() }.getOrNull()
    if (gitMarker != null) return candidate
    candidate = candidate.parent
  }
  return null
}

private enum class VerifyRepoCorrelation {
  SAME_REPO,
  OTHER_REPO,
  UNKNOWN,
}

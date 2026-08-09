package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskContinuationCandidate
import skillbill.application.featuretask.model.FeatureTaskContinuationLiveness
import skillbill.application.featuretask.model.FeatureTaskContinuationLookupResult
import skillbill.application.workflow.goalContinuationFor
import skillbill.application.workflow.toSnapshot
import skillbill.error.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.error.LegacyProseWorkflowError
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.model.FeatureTaskRouteScope
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.model.FeatureTaskWorkflowCandidate
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

@Inject
class FeatureTaskContinuationLookupService(
  private val database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val decompositionManifestValidator: DecompositionManifestValidator,
) {
  private val engine = WorkflowEngine(workflowSnapshotValidator)

  fun claim(candidate: FeatureTaskContinuationCandidate, dbOverride: String? = null): Boolean =
    database.transaction(dbOverride) { unitOfWork ->
      unitOfWork.workflowStates.claimFeatureTaskContinuation(candidate.workflowId, candidate.updatedAt)
    }

  fun lookup(
    issueKey: String,
    repositoryIdentity: String,
    workflowId: String? = null,
    dbOverride: String? = null,
  ): FeatureTaskContinuationLookupResult = lookup(
    issueKey,
    repositoryIdentity,
    workflowId,
    dbOverride,
    FeatureTaskRouteScope.STANDALONE,
  )

  fun lookupGoalChild(
    issueKey: String,
    repositoryIdentity: String,
    workflowId: String,
    dbOverride: String? = null,
  ): FeatureTaskContinuationLookupResult = lookup(
    issueKey,
    repositoryIdentity,
    workflowId,
    dbOverride,
    FeatureTaskRouteScope.GOAL_CHILD,
  )

  private fun lookup(
    issueKey: String,
    repositoryIdentity: String,
    workflowId: String?,
    dbOverride: String?,
    routeScope: FeatureTaskRouteScope,
  ): FeatureTaskContinuationLookupResult = database.read(dbOverride) { unitOfWork ->
    val normalizedIssueKey = FeatureTaskExecutionIdentityPolicy.validateLookupRequest(issueKey, repositoryIdentity)
    val candidates = when (routeScope) {
      FeatureTaskRouteScope.STANDALONE -> unitOfWork.workflowStates.findStandaloneFeatureTaskCandidates(
        normalizedIssueKey,
        repositoryIdentity,
      )
      FeatureTaskRouteScope.GOAL_CHILD -> unitOfWork.workflowStates.findGoalChildFeatureTaskCandidates(
        normalizedIssueKey,
        repositoryIdentity,
      )
    }
    val selected = workflowId?.let { selector ->
      listOf(
        candidates.singleOrNull { it.workflow.workflowId == selector }
          ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(
            "lookup request",
            "workflow selector '$selector' does not match this issue and repository",
          ),
      )
    } ?: candidates
    val identityLess = selected.firstOrNull { it.identity == null }
    if (identityLess != null) {
      return@read FeatureTaskContinuationLookupResult.NeedsIdentityRepair(
        workflowId = identityLess.workflow.workflowId,
        summary = "Workflow '${identityLess.workflow.workflowId}' has no immutable execution identity; " +
          "run `skill-bill feature-task repair-identity` for that workflow id before continuing.",
      )
    }
    val validated = selected.map {
      project(
        it,
        unitOfWork.workflowStates.getFeatureTaskRuntimeWorkerOwnership(it.workflow.workflowId),
        routeScope,
      )
    }
    val classified = classify(validated)
    // A goal parent can only be surfaced when the caller did not pin a specific feature-task
    // workflow, and only once no feature-task row answers the lookup.
    if (classified != FeatureTaskContinuationLookupResult.NoMatch ||
      workflowId != null ||
      routeScope != FeatureTaskRouteScope.STANDALONE
    ) {
      return@read classified
    }
    unitOfWork.workflowStates.goalContinuationFor(
      normalizedIssueKey,
      repositoryIdentity,
      decompositionManifestValidator,
    )?.let(FeatureTaskContinuationLookupResult::GoalContinuation) ?: classified
  }

  private fun project(
    candidate: FeatureTaskWorkflowCandidate,
    ownership: FeatureTaskRuntimeWorkerOwnership?,
    routeScope: FeatureTaskRouteScope,
  ): FeatureTaskContinuationCandidate {
    val identity = requireNotNull(candidate.identity) {
      invalidIdentity(candidate, "missing immutable execution identity")
    }
    FeatureTaskExecutionIdentityPolicy.validate(identity)
    if (identity.routeScope != routeScope) {
      invalidIdentity(
        candidate,
        "${routeScope.wireValue} lookup returned route_scope '${identity.routeScope.wireValue}'",
      )
    }
    if (identityConflictsWithWorkflow(identity, candidate)) {
      invalidIdentity(candidate, "immutable identity conflicts with workflow snapshot")
    }
    // SKILL-175: the prose engine is retired. A PROSE-mode candidate is quarantined here rather than
    // classified as resumable — continuation of a legacy prose row must loud-fail, not degrade into a
    // (deleted) prose definition or reinterpret the row as a runtime candidate.
    if (identity.mode == FeatureTaskWorkflowMode.PROSE) {
      throw LegacyProseWorkflowError(candidate.workflow.workflowId, candidate.workflow.issueKey)
    }
    val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition
    engine.snapshotView(definition, candidate.workflow.toSnapshot())
    val status = candidate.workflow.workflowStatus
    return FeatureTaskContinuationCandidate(
      workflowId = candidate.workflow.workflowId,
      mode = identity.mode,
      status = status,
      currentStep = candidate.workflow.currentStepId,
      governedSpecPath = identity.governedSpecPath,
      updatedAt = candidate.workflow.updatedAt,
      liveness = if (status == "running") {
        ownership?.let {
          FeatureTaskContinuationLiveness(
            classification = "worker_ownership_recorded",
            lastEvidenceAt = it.heartbeatAt,
            evidence = "Runtime worker ownership is fenced at generation ${it.generation}; exact process liveness " +
              "must be verified before takeover.",
          )
        } ?: FeatureTaskContinuationLiveness(
          classification = "ownership_unavailable",
          lastEvidenceAt = candidate.workflow.updatedAt,
          evidence = "The workflow is running without verifiable worker ownership; operator repair is required.",
        )
      } else {
        null
      },
      summary = when (status) {
        "running" -> "Workflow is already running; inspect liveness before recovery."
        in TERMINAL_STATUSES -> "Workflow is terminal with status '$status'."
        else -> "Resume from '${candidate.workflow.currentStepId}' using durable workflow artifacts."
      },
    )
  }

  private fun identityConflictsWithWorkflow(
    identity: skillbill.ports.persistence.model.FeatureTaskExecutionIdentity,
    candidate: FeatureTaskWorkflowCandidate,
  ): Boolean {
    val workflow = candidate.workflow
    val modeConflicts = workflow.mode?.let { it != identity.mode } ?: false
    return identity.workflowId != workflow.workflowId ||
      modeConflicts ||
      identity.normalizedIssueKey != workflow.issueKey?.trim()?.uppercase()
  }

  private fun invalidIdentity(candidate: FeatureTaskWorkflowCandidate, reason: String): Nothing =
    throw InvalidFeatureTaskExecutionIdentitySchemaError(candidate.workflow.workflowId, reason)

  private fun classify(candidates: List<FeatureTaskContinuationCandidate>): FeatureTaskContinuationLookupResult {
    if (candidates.isEmpty()) return FeatureTaskContinuationLookupResult.NoMatch
    val eligible = candidates.filterNot { it.status in TERMINAL_STATUSES }
    if (eligible.size > 1) return FeatureTaskContinuationLookupResult.Ambiguous(eligible)
    if (eligible.size == 1) {
      val candidate = eligible.single()
      return if (candidate.status == "running") {
        FeatureTaskContinuationLookupResult.AlreadyRunning(candidate)
      } else {
        FeatureTaskContinuationLookupResult.Resumable(candidate)
      }
    }
    return FeatureTaskContinuationLookupResult.TerminalOnly(candidates)
  }

  private companion object {
    val TERMINAL_STATUSES = setOf("completed", "failed", "abandoned")
  }
}

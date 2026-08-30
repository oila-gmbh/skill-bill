package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskContinuationCandidate
import skillbill.application.featuretask.model.FeatureTaskContinuationLookupQuery
import skillbill.application.featuretask.model.FeatureTaskContinuationLiveness
import skillbill.application.featuretask.model.FeatureTaskContinuationLookupResult
import skillbill.application.workflow.goalContinuationFor
import skillbill.application.workflow.toSnapshot
import skillbill.error.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.error.LegacyProseWorkflowError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.UnitOfWork
import skillbill.ports.featuretask.model.FeatureTaskExecutionIdentity
import skillbill.ports.featuretask.model.FeatureTaskRouteScope
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.featuretask.model.FeatureTaskWorkflowCandidate
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.WorkflowSnapshotValidator
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
    FeatureTaskContinuationLookupQuery(
      issueKey = issueKey,
      repositoryIdentity = repositoryIdentity,
      workflowId = workflowId,
      dbOverride = dbOverride,
      routeScope = FeatureTaskRouteScope.STANDALONE,
    ),
  )

  fun lookupGoalChild(
    issueKey: String,
    repositoryIdentity: String,
    workflowId: String,
    dbOverride: String? = null,
  ): FeatureTaskContinuationLookupResult = lookup(
    FeatureTaskContinuationLookupQuery(
      issueKey = issueKey,
      repositoryIdentity = repositoryIdentity,
      workflowId = workflowId,
      dbOverride = dbOverride,
      routeScope = FeatureTaskRouteScope.GOAL_CHILD,
    ),
  )

  fun lookupIfPresent(
    issueKey: String,
    repositoryIdentity: String,
    workflowId: String? = null,
    dbOverride: String? = null,
  ): FeatureTaskContinuationLookupResult = lookup(
    FeatureTaskContinuationLookupQuery(
      issueKey = issueKey,
      repositoryIdentity = repositoryIdentity,
      workflowId = workflowId,
      dbOverride = dbOverride,
      routeScope = FeatureTaskRouteScope.STANDALONE,
      readIfPresent = true,
    ),
  )

  private fun lookup(query: FeatureTaskContinuationLookupQuery): FeatureTaskContinuationLookupResult {
    val lookup = { unitOfWork: UnitOfWork ->
      executeFeatureTaskContinuationLookup(
        query = query,
        unitOfWork = unitOfWork,
        decompositionManifestValidator = decompositionManifestValidator,
        project = ::project,
        classify = ::classify,
      )
    }
    return if (query.readIfPresent) {
      database.readIfPresent(query.dbOverride, lookup) ?: FeatureTaskContinuationLookupResult.NoMatch
    } else {
      database.read(query.dbOverride, lookup)
    }
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
    identity: FeatureTaskExecutionIdentity,
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
    if (eligible.size > 1) return FeatureTaskContinuationLookupResult.Ambiguous(candidates)
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

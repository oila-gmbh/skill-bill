package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskContinuationCandidate
import skillbill.application.featuretask.model.FeatureTaskContinuationLiveness
import skillbill.application.featuretask.model.FeatureTaskContinuationLookupResult
import skillbill.application.workflow.toSnapshot
import skillbill.error.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.model.FeatureTaskRouteScope
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.model.FeatureTaskWorkflowCandidate
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.implement.FeatureImplementWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

@Inject
class FeatureTaskContinuationLookupService(
  private val database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
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
  ): FeatureTaskContinuationLookupResult = database.read(dbOverride) { unitOfWork ->
    val normalizedIssueKey = FeatureTaskExecutionIdentityPolicy.validateLookupRequest(issueKey, repositoryIdentity)
    val candidates = unitOfWork.workflowStates.findStandaloneFeatureTaskCandidates(
      normalizedIssueKey,
      repositoryIdentity,
    )
    val validated = candidates.map {
      it to project(it, unitOfWork.workflowStates.getFeatureTaskRuntimeWorkerOwnership(it.workflow.workflowId))
    }
    val selected = workflowId?.let { selector ->
      listOf(
        validated.singleOrNull { it.first.workflow.workflowId == selector }
          ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(
            "lookup request",
            "workflow selector '$selector' does not match this issue and repository",
          ),
      )
    } ?: validated
    classify(selected.map { it.second })
  }

  private fun project(
    candidate: FeatureTaskWorkflowCandidate,
    ownership: FeatureTaskRuntimeWorkerOwnership?,
  ): FeatureTaskContinuationCandidate {
    val identity = requireNotNull(candidate.identity) {
      invalidIdentity(candidate, "missing immutable execution identity")
    }
    FeatureTaskExecutionIdentityPolicy.validate(identity)
    if (identity.routeScope != FeatureTaskRouteScope.STANDALONE) {
      invalidIdentity(candidate, "standalone lookup returned route_scope '${identity.routeScope.wireValue}'")
    }
    if (identityConflictsWithWorkflow(identity, candidate)) {
      invalidIdentity(candidate, "immutable identity conflicts with workflow snapshot")
    }
    val definition = when (identity.mode) {
      FeatureTaskWorkflowMode.PROSE -> FeatureImplementWorkflowDefinition.definition
      FeatureTaskWorkflowMode.RUNTIME -> FeatureTaskRuntimePhaseWorkflowDefinition.definition
    }
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

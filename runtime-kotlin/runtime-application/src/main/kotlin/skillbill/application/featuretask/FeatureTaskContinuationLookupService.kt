package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskContinuationCandidate
import skillbill.application.featuretask.model.FeatureTaskContinuationLookupResult
import skillbill.application.normalizeRequiredIssueKey
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.model.FeatureTaskWorkflowCandidate

@Inject
class FeatureTaskContinuationLookupService(private val database: DatabaseSessionFactory) {
  fun lookup(
    issueKey: String,
    repositoryIdentity: String,
    workflowId: String? = null,
    dbOverride: String? = null,
  ): FeatureTaskContinuationLookupResult = database.read(dbOverride) { unitOfWork ->
    require(repositoryIdentity.startsWith(REPOSITORY_IDENTITY_PREFIX)) { "Invalid repository identity." }
    val candidates = unitOfWork.workflowStates.findStandaloneFeatureTaskCandidates(
      normalizeRequiredIssueKey(issueKey).uppercase(),
      repositoryIdentity,
    )
    val selected = workflowId?.let { selector ->
      listOf(candidates.singleOrNull { it.workflow.workflowId == selector }
        ?: error("Workflow selector '$selector' does not match this issue and repository."))
    } ?: candidates
    classify(selected.map(::project))
  }

  private fun project(candidate: FeatureTaskWorkflowCandidate): FeatureTaskContinuationCandidate {
    val identity = requireNotNull(candidate.identity) {
      "Feature-task workflow '${candidate.workflow.workflowId}' is missing immutable execution identity."
    }
    require(identity.workflowId == candidate.workflow.workflowId && identity.mode == candidate.workflow.mode) {
      "Feature-task workflow '${candidate.workflow.workflowId}' has conflicting immutable execution identity."
    }
    val status = candidate.workflow.workflowStatus
    return FeatureTaskContinuationCandidate(
      workflowId = candidate.workflow.workflowId,
      mode = identity.mode,
      status = status,
      currentStep = candidate.workflow.currentStepId,
      governedSpecPath = identity.governedSpecPath,
      updatedAt = candidate.workflow.updatedAt,
      summary = when (status) {
        "running" -> "Workflow is already running; inspect liveness before recovery."
        in TERMINAL_STATUSES -> "Workflow is terminal with status '$status'."
        else -> "Resume from '${candidate.workflow.currentStepId}' using durable workflow artifacts."
      },
    )
  }

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
    const val REPOSITORY_IDENTITY_PREFIX = "repo-root-realpath-v1:"
    val TERMINAL_STATUSES = setOf("completed", "failed", "abandoned")
  }
}

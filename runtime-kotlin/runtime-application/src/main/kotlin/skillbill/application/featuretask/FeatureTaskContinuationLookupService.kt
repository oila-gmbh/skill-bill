package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskContinuationCandidate
import skillbill.application.featuretask.model.FeatureTaskContinuationLookupResult
import skillbill.application.normalizeRequiredIssueKey
import skillbill.application.workflow.toSnapshot
import skillbill.error.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.model.FeatureTaskWorkflowCandidate
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.implement.FeatureImplementWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.contracts.workflow.FEATURE_TASK_EXECUTION_IDENTITY_CONTRACT_VERSION
import skillbill.ports.persistence.model.FeatureTaskRouteScope

@Inject
class FeatureTaskContinuationLookupService(
  private val database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
) {
  private val engine = WorkflowEngine(workflowSnapshotValidator)
  fun lookup(
    issueKey: String,
    repositoryIdentity: String,
    workflowId: String? = null,
    dbOverride: String? = null,
  ): FeatureTaskContinuationLookupResult = database.read(dbOverride) { unitOfWork ->
    require(repositoryIdentity.startsWith(REPOSITORY_IDENTITY_PREFIX) && repositoryIdentity.length > REPOSITORY_IDENTITY_PREFIX.length) {
      "Invalid repository identity."
    }
    val candidates = unitOfWork.workflowStates.findStandaloneFeatureTaskCandidates(
      normalizeRequiredIssueKey(issueKey).uppercase(),
    )
    val validated = candidates.map { it to project(it) }
      .filter { (row, _) ->
        val identity = row.identity
        identity?.repositoryIdentity == repositoryIdentity &&
          identity.routeScope == FeatureTaskRouteScope.STANDALONE
      }
    val selected = workflowId?.let { selector ->
      listOf(validated.singleOrNull { it.first.workflow.workflowId == selector }
        ?: error("Workflow selector '$selector' does not match this issue and repository."))
    } ?: validated
    classify(selected.map { it.second })
  }

  private fun project(candidate: FeatureTaskWorkflowCandidate): FeatureTaskContinuationCandidate {
    val identity = requireNotNull(candidate.identity) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(
        candidate.workflow.workflowId,
        "missing immutable execution identity",
      )
    }
    validateIdentity(identity)
    if (identity.workflowId != candidate.workflow.workflowId || identity.mode != candidate.workflow.mode ||
      identity.normalizedIssueKey != normalizeRequiredIssueKey(candidate.workflow.issueKey.orEmpty()).uppercase()
    ) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(
        candidate.workflow.workflowId,
        "immutable identity conflicts with workflow snapshot",
      )
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
      summary = when (status) {
        "running" -> "Workflow is already running; inspect liveness before recovery."
        in TERMINAL_STATUSES -> "Workflow is terminal with status '$status'."
        else -> "Resume from '${candidate.workflow.currentStepId}' using durable workflow artifacts."
      },
    )
  }

  private fun validateIdentity(identity: skillbill.ports.persistence.model.FeatureTaskExecutionIdentity) {
    val failure = when {
      identity.contractVersion != FEATURE_TASK_EXECUTION_IDENTITY_CONTRACT_VERSION ->
        "contract_version must be $FEATURE_TASK_EXECUTION_IDENTITY_CONTRACT_VERSION"
      !ISSUE_KEY_PATTERN.matches(identity.normalizedIssueKey) -> "normalized_issue_key is malformed"
      !identity.repositoryIdentity.startsWith(REPOSITORY_IDENTITY_PREFIX) -> "repository_identity is malformed"
      !identity.governedSpecPath.startsWith(".feature-specs/") || !identity.governedSpecPath.endsWith(".md") ->
        "governed_spec_path is malformed"
      else -> null
    }
    failure?.let { throw InvalidFeatureTaskExecutionIdentitySchemaError(identity.workflowId, it) }
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
    val ISSUE_KEY_PATTERN = Regex("^[A-Z][A-Z0-9]*-[0-9]+$")
    val TERMINAL_STATUSES = setOf("completed", "failed", "abandoned")
  }
}

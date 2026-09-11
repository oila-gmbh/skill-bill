package skillbill.infrastructure.sqlite.goalrunner

import skillbill.goalrunner.STALENESS_EVIDENCE_WINDOW
import skillbill.goalrunner.declaredProgressEventFrom
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.parseInstantOrNull
import skillbill.goalrunner.terminalOutcomeFor
import skillbill.infrastructure.sqlite.decomposition.decodeArtifactsimport skillbill.ports.goalrunner.persistence.model.GoalContinuationCandidate
import skillbill.ports.goalrunner.persistence.model.GoalRunnerBlockWrite
import skillbill.ports.goalrunner.persistence.model.StaleRunningCandidatesBlockRequest
import skillbill.ports.goalrunner.persistence.parseInstantOrNull
import skillbill.ports.goalrunner.persistence.staleRunningReason
import skillbill.ports.goalrunner.persistence.terminalOutcomeFor
import skillbill.ports.goalrunner.runner.model.GoalRunnerReconcileGate
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.decomposition.runtime.decodeArtifacts
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.model.FeatureTaskRuntimeSnapshot
import skillbill.ports.workflow.persistence.model.WorkflowFamily
import skillbill.ports.workflow.persistence.toSnapshot
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.goal.GoalObservabilityEventValidator
import skillbill.workflow.goal.model.goalObservabilityLatestEventForLiveness
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal data class WorkflowGoalRunnerOutcomeReconcileRuntime(
  val engine: WorkflowEngine,
  val gitOperations: WorkflowGitOperations,
  val goalObservabilityEventValidator: GoalObservabilityEventValidator,
  val clock: Clock,
  val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
)

internal data class WorkflowGoalRunnerOutcomeReconcilePersistence(
  val blockWrites: WorkflowGoalRunnerBlockWrites,
  val terminalPersistence: WorkflowGoalRunnerOutcomeTerminalPersistence,
)

internal class WorkflowGoalRunnerOutcomeReconcile(
  runtime: WorkflowGoalRunnerOutcomeReconcileRuntime,
  persistence: WorkflowGoalRunnerOutcomeReconcilePersistence,
) {
  private val engine = runtime.engine
  private val gitOperations = runtime.gitOperations
  private val goalObservabilityEventValidator = runtime.goalObservabilityEventValidator
  private val blockWrites = persistence.blockWrites
  private val terminalPersistence = persistence.terminalPersistence
  private val clock = runtime.clock
  private val diagnostics = runtime.diagnostics

  fun reconcileAuthoritativeOutcomesInTransaction(
    unitOfWork: UnitOfWork,
    issueKey: String,
    activeWorkflowIds: Set<String>,
    gate: GoalRunnerReconcileGate,
    repoRoot: Path?,
  ): Map<Int, GoalRunnerStoredOutcome> {
    val normalizedIssueKey = issueKey.trim()
    val activeSet = activeWorkflowIds.map(String::trim).filter(String::isNotBlank).toSet()
    displaceStaleBlockedOutcomes(unitOfWork, normalizedIssueKey)
    val initialCandidates = loadContinuationCandidates(unitOfWork.workflowStates, normalizedIssueKey, repoRoot)
    persistMeasuredCompletions(unitOfWork, initialCandidates, repoRoot)
    val initialAuthoritative = initialCandidates.authoritativeOutcomesBySubtask()
    blockStaleRunningCandidates(
      StaleRunningCandidatesBlockRequest(
        unitOfWork = unitOfWork,
        normalizedIssueKey = normalizedIssueKey,
        candidates = initialCandidates,
        initialAuthoritative = initialAuthoritative,
        activeSet = activeSet,
        gate = gate,
      ),
    )
    return loadContinuationCandidates(unitOfWork.workflowStates, normalizedIssueKey, repoRoot)
      .authoritativeOutcomesBySubtask()
  }

  private fun displaceStaleBlockedOutcomes(unitOfWork: UnitOfWork, issueKey: String) {
    loadContinuationCandidates(unitOfWork.workflowStates, issueKey, repoRoot = null)
      .forEach { candidate ->
        terminalPersistence.displaceStaleBlockedContinuationOutcomeIfPresent(
          unitOfWork.workflowStates,
          candidate.snapshot.workflowId,
          candidate.goalContinuation.issueKey,
          candidate.goalContinuation.subtaskId,
        )
      }
  }

  private fun persistMeasuredCompletions(
    unitOfWork: UnitOfWork,
    candidates: List<GoalContinuationCandidate>,
    repoRoot: Path?,
  ) {
    if (repoRoot == null) return
    candidates
      .filter { candidate -> candidate.outcome?.status == GoalRunnerTerminalStatus.COMPLETE }
      .forEach { candidate ->
        terminalPersistence.persistMeasuredCompletion(
          unitOfWork.workflowStates,
          candidate.snapshot.workflowId,
          candidate.goalContinuation.issueKey,
          candidate.goalContinuation.subtaskId,
          requireNotNull(candidate.outcome),
        )
      }
  }

  private fun blockStaleRunningCandidates(request: StaleRunningCandidatesBlockRequest) {
    request.candidates
      .filter { candidate ->
        isStaleRunningCandidate(candidate, request.initialAuthoritative, request.activeSet, request.gate)
      }
      .forEach { stale ->
        val authoritative = request.initialAuthoritative[stale.goalContinuation.subtaskId]
        val blockedReason = staleRunningReason(
          staleWorkflowId = stale.snapshot.workflowId,
          issueKey = request.normalizedIssueKey,
          subtaskId = stale.goalContinuation.subtaskId,
          authoritative = authoritative,
        )
        blockWrites.markBlocked(
          GoalRunnerBlockWrite(
            family = stale.family,
            record = stale.snapshot,
            blockedReason = blockedReason,
            lastResumableStep = stale.snapshot.currentStepId,
            workflowStates = request.unitOfWork.workflowStates,
            supervisionEvent = null,
          ),
        )
      }
  }

  private fun isStaleRunningCandidate(
    candidate: GoalContinuationCandidate,
    initialAuthoritative: Map<Int, GoalRunnerStoredOutcome>,
    activeSet: Set<String>,
    gate: GoalRunnerReconcileGate,
  ): Boolean {
    if (candidate.snapshot.workflowStatus != "running") return false
    if (candidate.outcome?.status == GoalRunnerTerminalStatus.COMPLETE) return false
    val authoritative = initialAuthoritative[candidate.goalContinuation.subtaskId]
    val inactive = candidate.snapshot.workflowId !in activeSet
    val supersededByAuthoritative = authoritative?.status == GoalRunnerTerminalStatus.COMPLETE &&
      authoritative.workflowId != candidate.snapshot.workflowId
    val staleByInactivity = if (gate.requireStalenessEvidence) {
      inactive && candidateIsStale(candidate)
    } else {
      gate.allowInactiveReconciliation && inactive
    }
    return staleByInactivity || supersededByAuthoritative
  }

  fun loadContinuationCandidates(
    workflowStates: WorkflowStateRepository,
    issueKey: String,
    repoRoot: Path? = null,
  ): List<GoalContinuationCandidate> = workflowStates.listFeatureTaskRuntimeSnapshots(Int.MAX_VALUE).mapNotNull { raw ->
    val ownership = snapshotOwnership(raw, issueKey)
    try {
      val snapshot = raw.workflow.toSnapshot()
      val family = WorkflowFamily.TASK_RUNTIME
      engine.snapshotView(family.definition, snapshot)
      val artifacts = decodeArtifacts(snapshot.artifactsJson)
      val goalContinuation = goalContinuation(artifacts) ?: return@mapNotNull null
      if (goalContinuation.issueKey != issueKey) {
        return@mapNotNull null
      }
      GoalContinuationCandidate(
        family = family,
        snapshot = snapshot,
        goalContinuation = goalContinuation,
        outcome = terminalOutcomeFor(snapshot, artifacts, goalContinuation) {
          repoRoot?.let { root -> gitOperations.headCommitSha(root).measuredCommitSha() }
        },
      )
    } catch (error: InvalidWorkflowStateSchemaError) {
      if (ownership != SnapshotOwnership.EXPLICIT_MISMATCH) {
        throw error
      }
      diagnostics.warning(
        "Skipped stale feature-task workflow '${raw.workflow.workflowId}': " +
          "schema validation failed (${redactedWorkflowStateFailure(error)}).",
      )
      null
    }
  }

  private fun snapshotOwnership(snapshot: FeatureTaskRuntimeSnapshot, issueKey: String): SnapshotOwnership = when {
    snapshot.workflow.issueKey?.trim()?.uppercase() == issueKey.trim().uppercase() ||
      snapshot.identity?.normalizedIssueKey == issueKey.trim().uppercase() -> SnapshotOwnership.REQUESTED
    snapshot.workflow.issueKey.isNullOrBlank() && snapshot.identity == null -> SnapshotOwnership.UNKNOWN
    else -> SnapshotOwnership.EXPLICIT_MISMATCH
  }

  private fun candidateIsStale(candidate: GoalContinuationCandidate): Boolean = runCatching {
    candidate.outcome?.status?.let { return@runCatching it != GoalRunnerTerminalStatus.COMPLETE }
    val now = clock.instant()
    val window = STALENESS_EVIDENCE_WINDOW
    val liveness = candidateLivenessInstants(candidate)
    val recent = liveness.any { signal -> Duration.between(signal, now).let { !it.isNegative && it <= window } }
    liveness.isNotEmpty() && !recent
  }.getOrDefault(false)

  private fun candidateLivenessInstants(candidate: GoalContinuationCandidate): List<Instant> {
    val artifacts = decodeArtifacts(candidate.snapshot.artifactsJson)
    val declared = declaredProgressEventFrom(artifacts)?.timestamp
    val observed = goalObservabilityLatestEventForLiveness(artifacts, goalObservabilityEventValidator)?.timestamp
    return listOfNotNull(declared, observed, candidate.snapshot.updatedAt).mapNotNull(::parseInstantOrNull)
  }
}

private enum class SnapshotOwnership {
  REQUESTED,
  EXPLICIT_MISMATCH,
  UNKNOWN,
}

private fun redactedWorkflowStateFailure(error: InvalidWorkflowStateSchemaError): String {
  val type = error::class.simpleName.orEmpty()
  val message = error.message.orEmpty()
  return when {
    "malformed JSON" in message -> "$type: malformed JSON"
    "must decode to a JSON array" in message -> "$type: malformed JSON"
    "must decode to a JSON object" in message -> "$type: malformed JSON"
    else -> type
  }
}

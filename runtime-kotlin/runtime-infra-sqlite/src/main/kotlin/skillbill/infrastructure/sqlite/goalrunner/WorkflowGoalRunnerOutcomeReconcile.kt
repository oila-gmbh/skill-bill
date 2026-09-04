package skillbill.infrastructure.sqlite.goalrunner

import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.goalrunner.persistence.STALENESS_EVIDENCE_WINDOW
import skillbill.ports.goalrunner.persistence.authoritativeOutcomesBySubtask
import skillbill.ports.goalrunner.persistence.declaredProgressEventFrom
import skillbill.ports.goalrunner.persistence.goalContinuation
import skillbill.ports.goalrunner.persistence.model.GoalContinuationCandidate
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
import skillbill.ports.workflow.persistence.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.goal.GoalObservabilityEventValidator
import skillbill.workflow.goal.model.goalObservabilityLatestEventFromArtifacts
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal class WorkflowGoalRunnerOutcomeReconcile(
  private val engine: WorkflowEngine,
  private val gitOperations: WorkflowGitOperations,
  private val goalObservabilityEventValidator: GoalObservabilityEventValidator,
  private val blockWrites: WorkflowGoalRunnerBlockWrites,
  private val terminalPersistence: WorkflowGoalRunnerOutcomeTerminalPersistence,
  private val clock: Clock,
) {
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
  ): List<GoalContinuationCandidate> = listOf(WorkflowFamily.TASK_RUNTIME).flatMap { family ->
    family.list(workflowStates, Int.MAX_VALUE).mapNotNull { snapshot ->
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
    }
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
    val observed = goalObservabilityLatestEventFromArtifacts(artifacts, goalObservabilityEventValidator)?.timestamp
    return listOfNotNull(declared, observed, candidate.snapshot.updatedAt).mapNotNull(::parseInstantOrNull)
  }
}

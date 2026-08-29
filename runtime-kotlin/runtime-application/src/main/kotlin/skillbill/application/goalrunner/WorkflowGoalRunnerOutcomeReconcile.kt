package skillbill.application.goalrunner

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.workflow.WorkflowFamily
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.db.UnitOfWork
import skillbill.ports.goalrunner.runner.model.GoalRunnerReconcileGate
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.goal.GoalObservabilityEventValidator
import skillbill.workflow.goal.model.goalObservabilityLatestEventFromArtifacts
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

internal class WorkflowGoalRunnerOutcomeReconcile(
  private val engine: WorkflowEngine,
  private val gitOperations: WorkflowGitOperations,
  private val goalObservabilityEventValidator: GoalObservabilityEventValidator,
  private val blockWrites: WorkflowGoalRunnerBlockWrites,
  private val terminalPersistence: WorkflowGoalRunnerOutcomeTerminalPersistence,
) {
  @Suppress("LongMethod")
  fun reconcileAuthoritativeOutcomesInTransaction(
    unitOfWork: UnitOfWork,
    issueKey: String,
    activeWorkflowIds: Set<String>,
    gate: GoalRunnerReconcileGate,
    repoRoot: Path?,
  ): Map<Int, GoalRunnerStoredOutcome> {
    val normalizedIssueKey = issueKey.trim()
    val activeSet = activeWorkflowIds.map(String::trim).filter(String::isNotBlank).toSet()
    loadContinuationCandidates(unitOfWork.workflowStates, normalizedIssueKey, repoRoot = null)
      .forEach { candidate ->
        terminalPersistence.displaceStaleBlockedContinuationOutcomeIfPresent(
          unitOfWork.workflowStates,
          candidate.snapshot.workflowId,
          candidate.goalContinuation.issueKey,
          candidate.goalContinuation.subtaskId,
        )
      }
    val initialCandidates = loadContinuationCandidates(unitOfWork.workflowStates, normalizedIssueKey, repoRoot)
    if (repoRoot != null) {
      initialCandidates
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
    val initialAuthoritative = initialCandidates.authoritativeOutcomesBySubtask()
    initialCandidates
      .filter { candidate ->
        if (candidate.snapshot.workflowStatus != "running") {
          return@filter false
        }
        if (candidate.outcome?.status == GoalRunnerTerminalStatus.COMPLETE) {
          return@filter false
        }
        val authoritative = initialAuthoritative[candidate.goalContinuation.subtaskId]
        val inactive = candidate.snapshot.workflowId !in activeSet
        val supersededByAuthoritative = authoritative?.status == GoalRunnerTerminalStatus.COMPLETE &&
          authoritative.workflowId != candidate.snapshot.workflowId
        val staleByInactivity = if (gate.requireStalenessEvidence) {
          inactive && candidateIsStale(candidate)
        } else {
          gate.allowInactiveReconciliation && inactive
        }
        staleByInactivity || supersededByAuthoritative
      }
      .forEach { stale ->
        val authoritative = initialAuthoritative[stale.goalContinuation.subtaskId]
        val blockedReason = staleRunningReason(
          staleWorkflowId = stale.snapshot.workflowId,
          issueKey = normalizedIssueKey,
          subtaskId = stale.goalContinuation.subtaskId,
          authoritative = authoritative,
        )
        blockWrites.markBlocked(
          GoalRunnerBlockWrite(
            family = stale.family,
            record = stale.snapshot,
            blockedReason = blockedReason,
            lastResumableStep = stale.snapshot.currentStepId,
            workflowStates = unitOfWork.workflowStates,
            supervisionEvent = null,
          ),
        )
      }
    return loadContinuationCandidates(unitOfWork.workflowStates, normalizedIssueKey, repoRoot)
      .authoritativeOutcomesBySubtask()
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
    val now = Instant.now()
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

package skillbill.application.featuretask

import skillbill.application.evidence.FeatureTaskRuntimeSharedReviewEvidenceResolved
import skillbill.application.evidence.FeatureTaskRuntimeSharedReviewEvidenceResolver
import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointScopeInput
import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemoryRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemorySection
import skillbill.application.diagnostics.model.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.application.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateAgentTriageLauncher
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.application.featuretask.validation.model.ValidationGateTriageResult
import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.goalrunner.UnaddressedFindingLedgerScope
import skillbill.application.featuretask.model.FeatureTaskRuntimeImplementationContinuation
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimePlanningStopDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeResolvedPhaseAgent
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.toProjectionPayload
import skillbill.application.workflow.repoRoot
import skillbill.config.model.PhaseCompactionDirective
import skillbill.config.model.PhaseModelDirective
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.FeatureTaskRuntimePhaseOrderViolationError
import skillbill.error.FeatureTaskRuntimePhaseOutputFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidFeatureTaskRuntimePhaseBriefingFramingError
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.model.UNADDRESSED_FINDING_REJECTED_DISPOSITION
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.workflow.gitops.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.captureIndexState
import skillbill.ports.workflow.gitops.headCommitMessage
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.pathContentIdentities
import skillbill.ports.workflow.gitops.repositoryCheckpointFingerprint
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.ports.workflow.gitops.repositoryOwnedPaths
import skillbill.ports.workflow.gitops.restoreIndexState
import skillbill.ports.workflow.gitops.runtimePhaseChangedPathsBetweenCommits
import skillbill.ports.workflow.gitops.runtimePhaseHeadCommit
import skillbill.ports.workflow.gitops.stagePaths
import skillbill.ports.workflow.gitops.stagedPaths
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.SpecIntentProjectionResolveRequest
import skillbill.review.context.model.SpecIntentResolution
import skillbill.review.model.ReviewFindingVerdict
import skillbill.telemetry.estimation.estimateTokens
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimeQualityGateRouting
import skillbill.workflow.taskruntime.FeatureTaskRuntimeTransitionFunction
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_KIND_NO_PROGRESS
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_KIND_WARN_THRESHOLD
import skillbill.workflow.taskruntime.model.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.CorrectiveRepairCapturedResponse
import skillbill.workflow.taskruntime.model.CorrectiveRepairDiagnosticLocator
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgressDecision
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewPassSequence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY
import skillbill.workflow.goal.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.goal.model.GoalSubtaskOperatorDecision
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION
import skillbill.workflow.taskruntime.model.ReviewPassResolution
import skillbill.workflow.taskruntime.model.acceptanceCriterionRefsFor
import skillbill.workflow.taskruntime.model.boundPriorGapNotes
import skillbill.workflow.taskruntime.model.detectAuditRepairNonProgress
import skillbill.workflow.taskruntime.model.requireAcceptedOutput
import skillbill.workflow.taskruntime.model.upsertRepairReceipt
import skillbill.workflow.taskruntime.model.validateDispositionCoverage
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.application.review.model.DiffResolutionException
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation
import java.time.Instant
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.review.context.model.ReviewContextBudgetExceededException
import skillbill.application.review.RuntimeOwnedReviewMode
import skillbill.application.review.model.StackDetectionException
import skillbill.application.goalrunner.StructuredGoalReviewFinding
import skillbill.workflow.taskruntime.model.UNPROVEN_REPOSITORY_FINGERPRINT
import skillbill.error.UnreadableSpecIntentProjectionError
import skillbill.application.review.model.UsageValidationException
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus


internal fun FeatureTaskRuntimeRunLoop.establishRemediationCheckpoint(precedingPhaseId: String, loopId: String): Boolean {
    val branch = resolvedBranch
    if (branch == null || FeatureTaskRuntimeBranchSetup.protectedBranchName(branch) != null) {
      return recordRemediationBaseIfNeeded(precedingPhaseId, loopId, commitSha = null, parentSha = null)
    }
    val head = phaseGates.gitOperations.currentBranch(request.repoRoot)
    if (!head.ok || head.value.trim() != branch.trim()) {
      return recordRemediationBaseIfNeeded(precedingPhaseId, loopId, commitSha = null, parentSha = null)
    }
    val scope = resolveCheckpointScope(precedingPhaseId, branch, ::remediationCheckpointBlockedReason) ?: return false
    return when (scope) {
      is FeatureTaskRuntimeCheckpointDecision.Skip ->
        recordRemediationBaseIfNeeded(precedingPhaseId, loopId, commitSha = null, parentSha = null)
      is FeatureTaskRuntimeCheckpointDecision.Block -> {
        blockAt(precedingPhaseId, scope.reason)
        false
      }
      is FeatureTaskRuntimeCheckpointDecision.Stage -> {
        if (scope.adoptedPaths.isNotEmpty()) {
          runCatching {
            diagnostics.warning(FeatureTaskRuntimeCheckpointScope.adoptionWarning(branch, scope.adoptedPaths))
          }
        }
        val committed = commitRemediationCheckpoint(
          precedingPhaseId = precedingPhaseId,
          branch = branch,
          loopId = loopId,
          ownedPaths = scope.ownedPaths,
        ) ?: return false
        recordRemediationBaseIfNeeded(
          precedingPhaseId = precedingPhaseId,
          loopId = loopId,
          commitSha = committed.commitSha,
          parentSha = committed.parentSha,
        )
      }
    }
  }

internal fun FeatureTaskRuntimeRunLoop.commitRemediationCheckpoint(
    precedingPhaseId: String,
    branch: String,
    loopId: String,
    ownedPaths: List<String>,
  ): RemediationCheckpointCommit? {
    val snapshot = phaseGates.gitOperations.captureIndexState(request.repoRoot, ownedPaths)
    if (!snapshot.ok) {
      blockCheckpoint(precedingPhaseId, branch, snapshot.error, ::remediationCheckpointBlockedReason)
      return null
    }
    val parentSha = phaseGates.gitOperations.headCommitSha(request.repoRoot)
      .takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank)
    val staged = phaseGates.gitOperations.stagePaths(request.repoRoot, ownedPaths)
    if (!staged.ok) {
      blockCheckpoint(
        precedingPhaseId,
        branch,
        withIndexRestoreOutcome(staged.error, ownedPaths, snapshot.value.orEmpty()),
        ::remediationCheckpointBlockedReason,
      )
      return null
    }
    val subtaskIdentity = subtaskCommitIdentity()
    val message = checkpointCommitMessage(
      branch = branch,
      phaseId = precedingPhaseId,
      loopId = loopId,
      identity = subtaskIdentity,
      intent = FeatureTaskRuntimeCheckpointMessage.INTENT_REMEDIATION,
    )
    val commit = writeSubtaskCommit(branch, message, subtaskIdentity)
    if (!commit.ok) {
      blockCheckpoint(
        precedingPhaseId,
        branch,
        withIndexRestoreOutcome(commit.error, ownedPaths, snapshot.value.orEmpty()),
        ::remediationCheckpointBlockedReason,
      )
      return null
    }
    val commitSha = commit.value.orEmpty().trim()
    if (commitSha.isBlank()) {
      blockCheckpoint(
        precedingPhaseId,
        branch,
        "remediation checkpoint commit returned an empty sha",
        ::remediationCheckpointBlockedReason,
      )
      return null
    }
    val recorded = recordCheckpointIdentity(
      precedingPhaseId = precedingPhaseId,
      branch = branch,
      loopId = loopId,
      ownedPaths = ownedPaths,
      parentSha = parentSha,
      commitSha = commitSha,
      blockedReason = ::remediationCheckpointBlockedReason,
    )
    if (!recorded) {
      rollbackRemediationCheckpointCommit(commitSha, parentSha, identityRecorded = false)
      return null
    }
    return RemediationCheckpointCommit(commitSha = commitSha, parentSha = parentSha)
  }

internal fun FeatureTaskRuntimeRunLoop.recordRemediationBaseIfNeeded(
    precedingPhaseId: String,
    loopId: String,
    commitSha: String?,
    parentSha: String?,
  ): Boolean {
    // Only the review_fix edge reserves a remediation review pass, so only it has a pre-fix base to
    // record. The audit_gap edge re-enters implement without one and must not be gated on it.
    if (loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) return true
    val recorded = recordRemediationBaseSha(precedingPhaseId, commitSha)
    if (recorded) return true
    if (commitSha != null) {
      rollbackRemediationCheckpointCommit(commitSha, parentSha, identityRecorded = true)
    }
    return false
  }

  /**
   * Restores the branch tip from the prior checkpoint identity commit when one exists; otherwise
   * removes the subtask commit and leaves the branch at its pre-subtask tip. Idempotent when HEAD
   * no longer names [commitSha].
   */
internal fun FeatureTaskRuntimeRunLoop.rollbackRemediationCheckpointCommit(commitSha: String, parentSha: String?, identityRecorded: Boolean) {
    val normalizedCommit = commitSha.trim()
    val head = phaseGates.gitOperations.headCommitSha(request.repoRoot)
    if (!head.ok || head.value.trim() != normalizedCommit) return
    val subtaskId = request.goalContinuation?.subtaskId?.toString()
      ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
    val identities = runCatching {
      recorder.loadCheckpointIdentities(request.workflowId, request.dbPathOverride)
    }.fold(
      onSuccess = { loaded -> loaded.orEmpty() },
      onFailure = { error ->
        recordRemediationRollbackDegradation(
          seam = "FeatureTaskRuntimeRunLoop.rollbackRemediationCheckpointCommit",
          valueUsed = request.workflowId,
          valueExpected = "checkpoint identities for rollback",
          cause = "loadCheckpointIdentities failed: " +
            error.message.orEmpty().ifBlank { error::class.simpleName.orEmpty() },
        )
        emptyList()
      },
    )
      .filter { it.issueKey == request.issueKey && it.subtaskId == subtaskId }
      .sortedBy { it.sequenceNumber }
    val restoreSha = remediationRollbackTargetSha(
      identities = identities,
      commitSha = normalizedCommit,
      parentSha = parentSha,
      identityRecorded = identityRecorded,
    ) ?: return
    val reset = phaseGates.gitOperations.resetSoftToCommit(request.repoRoot, restoreSha)
    if (!reset.ok) {
      recordRemediationRollbackDegradation(
        seam = "FeatureTaskRuntimeRunLoop.rollbackRemediationCheckpointCommit",
        valueUsed = restoreSha,
        valueExpected = "successful soft reset to restore target",
        cause = reset.error.ifBlank { "resetSoftToCommit failed" },
      )
    }
  }

internal fun FeatureTaskRuntimeRunLoop.recordRemediationRollbackDegradation(
    seam: String,
    valueUsed: String,
    valueExpected: String,
    cause: String,
  ) {
    goalContinuationRecorder.appendRemediationRollbackDegradationEvidence(
      workflowId = request.workflowId,
      signal = RemediationDegradationSignal(
        seam = seam,
        valueUsed = valueUsed,
        valueExpected = valueExpected,
        cause = cause,
      ),
      dbOverride = request.dbPathOverride,
    )
  }

internal fun FeatureTaskRuntimeRunLoop.remediationRollbackTargetSha(
    identities: List<FeatureTaskRuntimeCheckpointIdentity>,
    commitSha: String,
    parentSha: String?,
    identityRecorded: Boolean,
  ): String? {
    val fallback = parentSha?.trim()?.takeIf(String::isNotBlank)
    val predecessor = rollbackPredecessor(identities, commitSha, identityRecorded) ?: return fallback
    return resolvedPredecessorSha(predecessor) ?: fallback
  }

internal fun FeatureTaskRuntimeRunLoop.rollbackPredecessor(
    identities: List<FeatureTaskRuntimeCheckpointIdentity>,
    commitSha: String,
    identityRecorded: Boolean,
  ): FeatureTaskRuntimeCheckpointIdentity? {
    val currentIdentity = if (identityRecorded) identities.lastOrNull { it.commitSha == commitSha } else null
    return when {
      currentIdentity != null && currentIdentity.sequenceNumber > 0 ->
        identities.find { it.sequenceNumber == currentIdentity.sequenceNumber - 1 }
      currentIdentity != null -> null
      else -> identities.maxByOrNull { it.sequenceNumber }
    }
  }

internal fun FeatureTaskRuntimeRunLoop.resolvedPredecessorSha(predecessor: FeatureTaskRuntimeCheckpointIdentity): String? {
    val predecessorCommitSha = predecessor.commitSha.trim()
    if (predecessorCommitSha.isBlank()) {
      recordRemediationRollbackDegradation(
        seam = "FeatureTaskRuntimeRunLoop.remediationRollbackTargetSha",
        valueUsed = "(blank)",
        valueExpected = "resolvable predecessor identity commit",
        cause = "predecessor identity commit sha was missing or blank",
      )
      return null
    }
    val resolved = phaseGates.gitOperations.resolveCommit(request.repoRoot, predecessorCommitSha)
    val predecessorSha = resolved.value.orEmpty().trim().takeIf { resolved.ok && it.isNotBlank() }
    if (predecessorSha == null) {
      recordRemediationRollbackDegradation(
        seam = "FeatureTaskRuntimeRunLoop.remediationRollbackTargetSha",
        valueUsed = predecessorCommitSha,
        valueExpected = "resolvable predecessor identity commit",
        cause = resolved.error.takeIf { !resolved.ok && it.isNotBlank() }
          ?: "predecessor commit '$predecessorCommitSha' did not resolve",
      )
    }
    return predecessorSha
  }

  /**
   * A checkpoint commits the inventory this workflow owns and nothing else. The trigger is the OWNED
   * delta, not a non-blank `git status`: a tree dirty only with someone else's work has nothing for
   * this workflow to checkpoint, and committing it would attribute their changes to this run.
   */
internal fun FeatureTaskRuntimeRunLoop.checkpointEstablished(
    precedingPhaseId: String,
    loopId: String?,
    intent: String,
    blockedReason: (String, String) -> String,
  ): Boolean {
    val branch = resolvedBranch
    if (branch == null || FeatureTaskRuntimeBranchSetup.protectedBranchName(branch) != null) {
      return true
    }
    val head = phaseGates.gitOperations.currentBranch(request.repoRoot)
    if (!head.ok || head.value.trim() != branch.trim()) {
      return true
    }
    val scope = resolveCheckpointScope(precedingPhaseId, branch, blockedReason) ?: return false
    return when (scope) {
      is FeatureTaskRuntimeCheckpointDecision.Skip -> true
      is FeatureTaskRuntimeCheckpointDecision.Block -> {
        blockAt(precedingPhaseId, scope.reason)
        false
      }
      is FeatureTaskRuntimeCheckpointDecision.Stage -> {
        if (scope.adoptedPaths.isNotEmpty()) {
          runCatching {
            diagnostics.warning(FeatureTaskRuntimeCheckpointScope.adoptionWarning(branch, scope.adoptedPaths))
          }
        }
        commitCheckpoint(
          precedingPhaseId = precedingPhaseId,
          branch = branch,
          loopId = loopId,
          intent = intent,
          ownedPaths = scope.ownedPaths,
          blockedReason = blockedReason,
        )
      }
    }
  }

  /**
   * Resolves what this checkpoint may stage. Returns null when a git read failed and the phase was
   * already blocked; an unmeasurable inventory can never degrade into "owns nothing", because a
   * checkpoint reading that would skip silently and leave the phase's work uncommitted.
   */

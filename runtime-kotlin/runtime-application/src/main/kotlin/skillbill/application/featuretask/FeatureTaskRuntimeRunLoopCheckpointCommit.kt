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


internal fun FeatureTaskRuntimeRunLoop.subtaskCommitIdentity(): FeatureTaskRuntimeSubtaskCommitIdentity =
    FeatureTaskRuntimeSubtaskCommitIdentity(
      issueKey = request.issueKey,
      subtaskId = request.goalContinuation?.subtaskId?.toString() ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
    )

internal fun FeatureTaskRuntimeRunLoop.checkpointCommitMessage(
    branch: String,
    phaseId: String,
    loopId: String?,
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
    intent: String,
  ): String {
    val subtaskName = request.goalContinuation?.subtaskName?.trim()?.takeIf(String::isNotBlank)
    // A standalone run has no manifest to carry a name, so only a goal continuation missing one is a
    // degradation worth a record.
    if (subtaskName == null && request.goalContinuation != null) {
      runCatching {
        diagnostics.warning(
          FeatureTaskRuntimeCheckpointMessage.missingSubtaskNameRecord(identity.issueKey, identity.subtaskId),
        )
      }
    }
    return FeatureTaskRuntimeCheckpointMessage.build(
      issueKey = request.issueKey,
      subtaskName = subtaskName,
      metadata = FeatureTaskRuntimeCheckpointMetadata(
        phaseId = phaseId,
        loopId = loopId,
        generation = checkpointGeneration(loopId),
        branch = branch,
        intent = intent,
      ),
      identity = identity,
    )
  }

internal fun FeatureTaskRuntimeRunLoop.subtaskCommitLedgerState(identity: FeatureTaskRuntimeSubtaskCommitIdentity): SubtaskCommitLedgerState {
    val read = runCatching { recorder.loadCheckpointIdentities(request.workflowId, request.dbPathOverride) }
    val identities = read.getOrNull()
    val cause = read.exceptionOrNull()
      ?.let { "the checkpoint-identity store could not be read (${it.message ?: it::class.simpleName})" }
      ?: "no workflow row recorded any checkpoint identity for this run".takeIf { identities == null }
    if (cause != null) {
      runCatching { diagnostics.warning(ledgerUnavailableRecord(identity, cause)) }
      return SubtaskCommitLedgerState(commitSha = null, nextSequenceNumber = 0)
    }
    val recorded = requireNotNull(identities)
    return SubtaskCommitLedgerState(
      commitSha = recorded
        .filter { it.issueKey == identity.issueKey && it.subtaskId == identity.subtaskId }
        .maxByOrNull { it.sequenceNumber }
        ?.commitSha,
      nextSequenceNumber = (recorded.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
    )
  }

internal fun FeatureTaskRuntimeRunLoop.ledgerUnavailableRecord(identity: FeatureTaskRuntimeSubtaskCommitIdentity, cause: String): String =
    "seam=FeatureTaskRuntimeRunLoop.subtaskCommitLedgerState value_used='no durable pointer, sequence 0' " +
      "value_expected=the recorded checkpoint-identity ledger for '${identity.issueKey}/${identity.subtaskId}' " +
      "cause=$cause"

  /**
   * One subtask, one branch commit: the first checkpoint with staged content creates it and every
   * later checkpoint amends it. Failures return an error result so the caller's existing index-restore
   * reporting handles them exactly as a failed create.
   */
internal fun FeatureTaskRuntimeRunLoop.writeSubtaskCommit(
    branch: String,
    message: String,
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  ): WorkflowGitOperationResult {
    val ledger = subtaskCommitLedgerState(identity)
    val headSha = phaseGates.gitOperations.headCommitSha(request.repoRoot)
      .takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank)
    val decision = FeatureTaskRuntimeSubtaskCommitResolver.decide(
      identity = identity,
      durableCommitSha = ledger.commitSha,
      head = FeatureTaskRuntimeSubtaskCommitHeadState(
        sha = headSha,
        commitMessage = if (ledger.commitSha == null && headSha != null) headCommitMessageOrNull() else null,
        isUnpushed = branchHasUnpushedCommits(branch),
      ),
      sequenceNumber = ledger.nextSequenceNumber,
    )
    return phaseGates.gitOperations.writeSubtaskCommitPreservingHistory(
      repoRoot = request.repoRoot,
      decision = decision,
      identity = identity,
      message = message,
      allowUnchangedIndex = false,
      record = { record -> runCatching { diagnostics.warning(record) } },
    )
  }

internal fun FeatureTaskRuntimeRunLoop.headCommitMessageOrNull(): String? =
    phaseGates.gitOperations.headCommitMessage(request.repoRoot).takeIf { it.ok }?.value

internal fun FeatureTaskRuntimeRunLoop.branchHasUnpushedCommits(branch: String): Boolean {
    val unpushed = phaseGates.gitOperations.localBranchHasUnpushedCommits(request.repoRoot, branch)
    return unpushed.ok && unpushed.value.orEmpty().trim().equals("true", ignoreCase = true)
  }

  /**
   * A failed restore is worse than the failure that triggered it: the index is now in an unknown
   * state and the operator has to know that before they touch the repository. It is reported in the
   * block reason rather than swallowed.
   */
internal fun FeatureTaskRuntimeRunLoop.withIndexRestoreOutcome(error: String, ownedPaths: List<String>, snapshot: String): String {
    val restored = phaseGates.gitOperations.restoreIndexState(request.repoRoot, ownedPaths, snapshot)
    return if (restored.ok) {
      "$error; the pre-checkpoint index was restored and the working tree is unchanged"
    } else {
      "$error; the pre-checkpoint index could NOT be restored (${restored.error}) — inspect " +
        "`git status` before committing anything yourself"
    }
  }

internal fun FeatureTaskRuntimeRunLoop.checkpointGeneration(loopId: String?): Int = loopId?.let { state.edgeIterationCount(it) } ?: 0

internal fun FeatureTaskRuntimeRunLoop.recordCheckpointIdentity(
    precedingPhaseId: String,
    branch: String,
    loopId: String?,
    ownedPaths: List<String>,
    parentSha: String?,
    commitSha: String,
    blockedReason: (String, String) -> String,
  ): Boolean {
    val recorded = runCatching {
      recorder.appendCheckpointIdentity(
        workflowId = request.workflowId,
        issueKey = request.issueKey,
        // A standalone feature-task run owns no decomposed subtask; the reserved literal keeps the
        // ref name well-formed instead of leaving the segment blank.
        subtaskId = request.goalContinuation?.subtaskId?.toString()
          ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
        branch = branch,
        phaseId = precedingPhaseId,
        loopId = loopId,
        generation = checkpointGeneration(loopId),
        parentSha = parentSha,
        ownedPaths = ownedPaths,
        commitSha = commitSha,
        dbOverride = request.dbPathOverride,
      )
    }
    // The commit already exists; without its identity record the review input has no immutable
    // checkpoint to build from and no later phase can prove what this commit was allowed to own.
    return if (recorded.getOrDefault(false)) {
      true
    } else {
      blockCheckpoint(
        precedingPhaseId,
        branch,
        "checkpoint commit '$commitSha' was created but its durable identity record could not be " +
          "written (${recorded.exceptionOrNull()?.message ?: "the workflow row was absent"}), so the " +
          "commit cannot be attributed to this workflow's authority boundary",
        blockedReason,
      )
    }
  }

internal fun FeatureTaskRuntimeRunLoop.blockCheckpoint(
    precedingPhaseId: String,
    branch: String,
    error: String,
    blockedReason: (String, String) -> String,
  ): Boolean {
    blockAt(precedingPhaseId, blockedReason(branch, error))
    return false
  }

internal fun FeatureTaskRuntimeRunLoop.matchingBackwardEdge(
    phaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
  ): FeatureTaskRuntimeBackwardEdge? =
    transitions.backwardEdges.firstOrNull { it.fromPhaseId == phaseId && it.triggeringVerdict == verdict }

  /**
   * Record-only resume reconstruction: a durable fix record carries this loop's context at the current
   * watermark but no `LOOP_EDGE` ledger row reconstructed it as in-flight, so the reserved iteration is
   * re-entered instead of a fresh one being allocated (no double-applied mutation). It is one-shot per
   * run — the loop is live-claimed the moment either this path or a live edge fire mints an iteration.
   * Without that bound the unbounded loop would re-satisfy this reconstruction on every re-review and
   * keep replaying the already-reviewed fix instead of earning the next remediation pass.
   */

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


internal fun FeatureTaskRuntimeRunLoop.resolveCheckpointScope(
    precedingPhaseId: String,
    branch: String,
    blockedReason: (String, String) -> String,
  ): FeatureTaskRuntimeCheckpointDecision? {
    val resolved = recorder.loadResolvedBranch(request.workflowId, request.dbPathOverride)
    val worktreeDelta = checkpointWorktreeDelta(resolved?.baselineOwnedPathsForCheckpoint().orEmpty())
      ?: return blockCheckpointScope(
        precedingPhaseId,
        branch,
        "the owned-path inventory could not be read",
        blockedReason,
      )
    val staged = phaseGates.gitOperations.stagedPaths(request.repoRoot)
    if (!staged.ok) {
      return blockCheckpointScope(precedingPhaseId, branch, staged.error, blockedReason)
    }
    val stagedPaths = staged.value.orEmpty().split(OWNED_PATH_DELIMITER)
      .map(String::trim)
      .filter(String::isNotBlank)
    val persistedOwned = resolved?.workflowOwnedPaths.orEmpty()
    // Governed feature specs a previous checkpoint already recorded as owned: the guard must not
    // re-report them as newly introduced, or the run hard-blocks forever on its own leftover state.
    val evictedFeatureSpecs = persistedOwned
      .filter { path -> isFeatureSpecPathForIssue(path, request.issueKey) }
      .toSet()
    val phaseWritten = phaseWrittenPaths(precedingPhaseId, worktreeDelta, persistedOwned)
      .filterNot { it in evictedFeatureSpecs }
    val writingIntroduced = writingPhaseIntroducedPaths(worktreeDelta)
    val seedOwned = (
      resolved?.workflowOwnedPaths.orEmpty() +
        phaseWritten.takeIf { mayExtendOwnedInventory(precedingPhaseId) }.orEmpty() +
        writingIntroduced
      ).distinct()
    val deletedPaths = absorbableDeletedPaths(
      deleted = checkpointDeletedPaths(),
      ownedOrIntroduced = seedOwned + phaseWritten,
    )
    val ownedInventory = reconcileCheckpointPathInventory(
      repoRoot = request.repoRoot,
      issueKey = request.issueKey,
      specReference = request.runInvariants.specReference,
      // The persisted inventory is the sole ownership authority. It is extended with the paths the
      // writing phases themselves wrote — never with whatever else happens to be dirty, which is how
      // someone else's concurrent edit used to be adopted and committed as this run's work.
      // Governed feature specs never become owned, so the persisted inventory contains implementation
      // paths only. The runtime never stages them; a human operator may already have committed them.
      // Delete sources that share a move parent with owned/introduced destinations are absorbed so a
      // package move can stage both halves.
      paths = (seedOwned + deletedPaths)
        .filterNot { path -> isFeatureSpecPathForIssue(path, request.issueKey) },
    )
    persistOwnedInventory(ownedInventory, resolved?.workflowOwnedPaths.orEmpty())
    checkpointOwnershipDecided = true
    // Nothing has been staged by this checkpoint yet, so every entry in the index arrived from
    // outside it. The scope decision keeps only the ones this run also owns: those are the genuinely
    // ambiguous overlaps. A purely foreign staged path is left alone, which is what lets a
    // concurrently prepared issue coexist without producing a false block.
    return FeatureTaskRuntimeCheckpointScope.decide(
      FeatureTaskRuntimeCheckpointScopeInput(
        issueKey = request.issueKey,
        ownedPaths = ownedInventory,
        phaseIntroducedPaths = phaseWritten,
        worktreeDeltaPaths = worktreeDelta,
        foreignStagedPaths = stagedPaths,
        concurrentlyModifiedOwnedPaths = concurrentlyModifiedOwnedPaths(precedingPhaseId, ownedInventory),
        deletedPaths = deletedPaths,
      ),
    )
  }

internal fun FeatureTaskRuntimeRunLoop.checkpointDeletedPaths(): List<String> {
    val status = phaseGates.gitOperations.worktreeStatus(request.repoRoot)
    if (!status.ok) return emptyList()
    return FeatureTaskRuntimePhaseSafetyPolicy.deletedPaths(status.value.orEmpty())
  }

  /**
   * Delete sources that belong to this run's package move: they share a parent directory with an
   * already-owned or writing-phase-introduced destination. Unrelated deletes stay foreign.
   */
internal fun FeatureTaskRuntimeRunLoop.absorbableDeletedPaths(deleted: List<String>, ownedOrIntroduced: List<String>): List<String> {
    if (deleted.isEmpty() || ownedOrIntroduced.isEmpty()) return emptyList()
    val anchors = ownedOrIntroduced.map { path -> path.substringBeforeLast('/', missingDelimiterValue = path) }
      .filter(String::isNotBlank)
      .distinct()
    return deleted.filter { removed ->
      val parent = removed.substringBeforeLast('/', missingDelimiterValue = removed)
      anchors.any { anchor ->
        parent == anchor ||
          anchor.startsWith("$parent/") ||
          parent.startsWith("$anchor/")
      }
    }
  }

internal fun FeatureTaskRuntimeRunLoop.mayExtendOwnedInventory(phaseId: String): Boolean = phaseId in INVENTORY_EXTENDING_PHASES

  /**
   * Every checkpoint seam runs from a reader phase (audit before review, review before the fix edge),
   * so the preceding phase can never widen ownership on its own. The paths a writing phase introduced
   * and left dirty would then be excluded from both the checkpoint commit and the pathspec-limited
   * review input: work that is neither committed, blocked, nor reviewed. The durable per-phase
   * manifests of the writing phases carry that attribution, so the inventory grows from those and
   * from nothing else.
   */
internal fun FeatureTaskRuntimeRunLoop.writingPhaseIntroducedPaths(worktreeDelta: List<String>): List<String> {
    val records = recorder.loadPhaseRecords(request.workflowId, request.dbPathOverride).orEmpty()
    val writingRecords = INVENTORY_EXTENDING_PHASES.mapNotNull { records[it] }
    if (writingRecords.isEmpty()) {
      if (worktreeDelta.isNotEmpty()) {
        runCatching {
          diagnostics.warning(
            "Feature-task-runtime checkpoint has no durable file manifest for any writing phase; " +
              "the whole working-tree delta is treated as this workflow's own writes.",
          )
        }
      }
      return worktreeDelta
    }
    // A writing phase owns both what it created and what it left dirty when it finished. A path that
    // only appears later, while a reader phase is running, was written by somebody else and stays out.
    val introduced = writingRecords.flatMap { it.fileManifestIntroduced + it.fileManifestAfter }.distinct()
    return FeatureTaskRuntimeCheckpointScope.phaseWrittenPaths(worktreeDelta, introduced)
  }

  /**
   * The subset of the working-tree delta the phase itself wrote, taken from its own durable
   * before/after file manifest. Without a manifest the run cannot tell its own writes from anyone
   * else's, so it degrades to the whole delta and records that it did: silently narrowing instead
   * would drop the phase's real work out of the checkpoint.
   */
internal fun FeatureTaskRuntimeRunLoop.phaseWrittenPaths(
    phaseId: String,
    worktreeDelta: List<String>,
    persistedInventory: List<String>,
  ): List<String> {
    val record = recorder.loadPhaseRecords(request.workflowId, request.dbPathOverride)?.get(phaseId)
    if (record == null) {
      if (worktreeDelta.isNotEmpty()) {
        runCatching {
          diagnostics.warning(
            "Feature-task-runtime checkpoint for phase '$phaseId' has no durable file manifest; " +
              "the whole working-tree delta is treated as the phase's own writes.",
          )
        }
      }
      return worktreeDelta
    }
    // What the phase itself introduced, plus the already-owned paths it left dirty. A path that was
    // dirty before the phase ran and that this workflow does not own belongs to someone else, and
    // is the case where the old since-baseline listing silently adopted a stranger's file.
    val owned = persistedInventory.toSet()
    val ownedStillDirty = record.fileManifestAfter.filter { it in owned }
    val manifest = (record.fileManifestIntroduced + ownedStillDirty).distinct()
    return FeatureTaskRuntimeCheckpointScope.phaseWrittenPaths(worktreeDelta, manifest)
  }

internal fun FeatureTaskRuntimeRunLoop.persistOwnedInventory(inventory: List<String>, persisted: List<String>) {
    if (inventory.sorted() == persisted.sorted()) return
    recorder.recordWorkflowOwnedPaths(request.workflowId, inventory, request.dbPathOverride)
  }

  /**
   * Owned paths whose content is no longer what the phase left there. The phase's own writes are
   * captured the moment it finishes, so a difference measured here is by definition somebody else's
   * edit landing while the run was between phases — the unstaged half of the overlap ambiguity.
   *
   * An absent capture (a resumed process, a phase that never launched here) yields no comparison
   * rather than a false accusation; the staged-overlap check still applies.
   */
internal fun FeatureTaskRuntimeRunLoop.concurrentlyModifiedOwnedPaths(phaseId: String, ownedPaths: List<String>): List<String> {
    val captured = phaseContentIdentities[phaseId] ?: return emptyList()
    val current = phaseGates.gitOperations.pathContentIdentities(request.repoRoot, ownedPaths)
    if (!current.ok) return emptyList()
    val now = parseContentIdentities(current.value.orEmpty())
    return captured.filter { (path, identity) -> path in now && now[path] != identity }.keys.sorted()
  }

internal fun FeatureTaskRuntimeRunLoop.blockCheckpointScope(
    precedingPhaseId: String,
    branch: String,
    error: String,
    blockedReason: (String, String) -> String,
  ): FeatureTaskRuntimeCheckpointDecision? {
    blockCheckpoint(precedingPhaseId, branch, error, blockedReason)
    return null
  }

internal fun FeatureTaskRuntimeRunLoop.checkpointWorktreeDelta(baselineOwnedPaths: List<String>): List<String>? {
    val owned = phaseGates.gitOperations.repositoryOwnedPaths(request.repoRoot)
    if (!owned.ok) return null
    val baseline = baselineOwnedPaths.toSet()
    return owned.value.orEmpty()
      .split(OWNED_PATH_DELIMITER)
      .map(String::trim)
      .filter(String::isNotBlank)
      .filterNot { it in baseline }
      .filterNot(FeatureTaskRuntimeCheckpointScope::isRuntimePrivatePath)
      .distinct()
      .sorted()
  }

  // The tracked-and-untracked baseline supersedes the untracked-only one; a run resolved before the
  // wider baseline existed still has the narrower one and must keep using it rather than none.
internal fun FeatureTaskRuntimeResolvedBranch.baselineOwnedPathsForCheckpoint(): List<String> =
    baselineOwnedPaths.ifEmpty { baselineUntrackedPaths }

  /**
   * The checkpoint commit has just captured the pre-fix tree, so [commitSha] (or HEAD when the
   * checkpoint was skipped) IS the pre-fix tree. The reserved remediation pass reviews
   * diff(this sha -> post-fix HEAD), which is what materializes a defect the remediation itself
   * introduces instead of leaving it to be caught incidentally.
   */
internal fun FeatureTaskRuntimeRunLoop.recordRemediationBaseSha(precedingPhaseId: String, commitSha: String? = null): Boolean {
    if (!isGoalContinuationRun(request)) return true
    // Without durable review state there is no reserved remediation pass to bound, so there is no
    // base to record and nothing this gate can protect.
    if (goalReviewStateOrNull() == null) return true
    val baseSha = commitSha?.trim()?.takeIf(String::isNotBlank) ?: run {
      val head = phaseGates.gitOperations.headCommitSha(request.repoRoot)
      if (!head.ok || head.value.isBlank()) {
        return blockRemediationBaseSha(precedingPhaseId, head.error.ifBlank { "HEAD resolved to an empty sha." })
      }
      head.value.trim()
    }
    return runCatching {
      goalContinuationRecorder.updateReviewState(request.workflowId, request.dbPathOverride) { state ->
        state.copy(remediationBaseSha = baseSha)
      }
    }.fold(
      onSuccess = { recorded ->
        if (recorded != null) {
          true
        } else {
          blockRemediationBaseSha(precedingPhaseId, "the review state could not be updated.")
        }
      },
      onFailure = { error -> blockRemediationBaseSha(precedingPhaseId, error.message.orEmpty()) },
    )
  }

internal fun FeatureTaskRuntimeRunLoop.completedImplementFixProducedOutputs(run: PhaseRun, outputMap: Map<String, Any?>): Map<String, Any?>? =
    outputMap
      .takeIf {
        run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX &&
          it["status"] == STATUS_COMPLETED
      }
      ?.let { JsonSupport.anyToStringAnyMap(it["produced_outputs"]).orEmpty() }


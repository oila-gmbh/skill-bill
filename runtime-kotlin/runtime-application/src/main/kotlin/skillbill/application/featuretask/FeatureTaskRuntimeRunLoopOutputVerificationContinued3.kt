package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.subtaskreview.GoalSubtaskReviewStructuredFindingsParse
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.ports.workflow.gitops.repositoryCheckpointFingerprint
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.ports.workflow.gitops.runtimePhaseChangedPathsBetweenCommits
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint

@Inject
class FeatureTaskRuntimeRunLoopOutputVerificationContinued3 {
  fun reviewFindingIdsForVerification(runLoop: FeatureTaskRuntimeRunLoop): Set<String> {
    val reviewOutput = runLoop.state.outputFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
      ?.normalizedOutput?.envelope
      ?: return emptySet()
    val recordedVerdicts = runLoop.recorder.recordedFindingVerdicts(reviewOutput, runLoop.request.dbPathOverride)
    return GoalSubtaskReviewStructuredFindingsParse.structuredFindings(reviewOutput, recordedVerdicts)
      .mapNotNull { it.findingId }
      .toSet()
  }

  /**
   * Rebuilds payload-free structural-repair evidence from digest/location fields carried on the
   * schema exception. Returns null when the throw had no correlated prior syntax repair.
   */
  fun structuralRepairEvidenceFromSchemaError(
    error: InvalidFeatureTaskRuntimePhaseOutputSchemaError,
  ): FeatureTaskRuntimePhaseOutputRepairEvidence? {
    val originalDigest = error.structuralRepairOriginalDigest
    val repairedDigest = error.structuralRepairRepairedDigest
    val format = error.structuralRepairFormat
    val operation = error.structuralRepairOperation
    val sourceLabel = error.structuralRepairSourceLabel
    val sourceOffset = error.structuralRepairSourceOffset
    val sourceLine = error.structuralRepairSourceLine
    val sourceColumn = error.structuralRepairSourceColumn
    if (
      listOf(
        originalDigest,
        repairedDigest,
        format,
        operation,
        sourceLabel,
        sourceOffset,
        sourceLine,
        sourceColumn,
      ).any { it == null }
    ) {
      return null
    }
    return FeatureTaskRuntimePhaseOutputRepairEvidence(
      format = FeatureTaskRuntimePhaseOutputFormat.fromWire(
        requireNotNull(format),
      ),
      originalDigest = requireNotNull(originalDigest),
      repairedDigest = requireNotNull(repairedDigest),
      operation = FeatureTaskRuntimePhaseOutputRepairOperation.fromWire(
        requireNotNull(operation),
      ),
      sourceLocation = FeatureTaskRuntimePhaseOutputSourceLocation(
        sourceLabel = requireNotNull(sourceLabel),
        offset = requireNotNull(sourceOffset),
        line = requireNotNull(sourceLine),
        column = requireNotNull(sourceColumn),
      ),
    )
  }

  internal fun persistAcceptedOutput(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: PersistAcceptedOutputArgs,
  ): AttemptResult {
    val run = args.run
    val iteration = args.iteration
    val normalizedOutput = args.normalizedOutput
    val repairEvidence = args.repairEvidence
    val observability = args.observability
    val fileManifest = args.fileManifest
    val repositoryFingerprint = args.repositoryFingerprint
    val outputText = normalizedOutput.canonicalJson
    if (run.validationGateFindings != null) {
      return runLoop.collaborators.outputVerificationContinued5.validationGatePersistedAttempt(
        run,
        iteration,
        normalizedOutput,
        repairEvidence,
        outputText,
      )
    }
    val reviewArgs = PhaseReviewPersistenceArgs(run, iteration, runLoop.observability, fileManifest)
    if (runLoop.collaborators.outputPersistence.isGoalReviewRun(run)) {
      runLoop.collaborators.outputPersistence.persistGoalReviewCompletion(
        runLoop,
        reviewArgs,
        normalizedOutput,
        repairEvidence,
      )?.let { outcome ->
        return AttemptResult.settled(outcome)
      }
    } else {
      runLoop.collaborators.outputVerificationContinued5.persistStandardAcceptedOutput(
        runLoop,
        PersistStandardAcceptedOutputArgs(
          accepted = PersistAcceptedOutputArgs(
            run = run,
            iteration = iteration,
            normalizedOutput = normalizedOutput,
            repairEvidence = repairEvidence,
            observability = runLoop.observability,
            fileManifest = fileManifest,
            repositoryFingerprint = repositoryFingerprint,
          ),
          outputText = outputText,
        ),
      )?.let { return it }
    }
    runLoop.observability.completedEvent(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    return runLoop.collaborators.outputVerificationContinued5.completedAttemptResult(
      run,
      iteration,
      outputText,
      normalizedOutput,
      repairEvidence,
    )
  }

  internal fun buildRepositoryCheckpoint(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
  ): FeatureTaskRuntimeRepositoryCheckpoint? {
    val resolvedBranchRecord = runLoop.recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
    runLoop.session.resolvedBranch = resolvedBranchRecord?.branch
    val goalReviewState = runLoop.goalContinuationRecorder.reviewState(
      run.request.workflowId,
      run.request.dbPathOverride,
    )
    val revisions = runLoop.collaborators.outputVerificationContinued4.resolveCheckpointRevisions(
      runLoop,
      run = run,
      headRevision = resolvedBranchRecord?.branch?.takeIf(String::isNotBlank) ?: "HEAD",
      baseRevision = goalReviewState?.reviewBaseSha ?: resolvedBranchRecord?.reviewBaseSha,
    ) ?: return null
    val ownedPaths = resolveCheckpointOwnedPaths(
      runLoop,
      run = run,
      persistedOwnedPaths = resolvedBranchRecord?.workflowOwnedPaths,
      baselineOwnedPaths = resolvedBranchRecord?.baselineOwnedPaths
        ?: goalReviewState?.baselineUntrackedPaths
        ?: resolvedBranchRecord?.baselineUntrackedPaths.orEmpty(),
      revisions = revisions,
    ) ?: return null
    val fingerprint = runLoop.gitOperations.repositoryCheckpointFingerprint(
      run.request.repoRoot,
      revisions.base,
      revisions.head,
      ownedPaths,
    ).takeIf { it.ok }?.value?.takeIf(String::isNotBlank) ?: return null
    return FeatureTaskRuntimeRepositoryCheckpoint(
      fingerprint = fingerprint,
      baseRef = revisions.base,
      headRef = revisions.head,
      workingTreeOwnedPaths = ownedPaths,
    )
  }

  internal fun resolveCheckpointOwnedPaths(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    persistedOwnedPaths: List<String>?,
    baselineOwnedPaths: List<String>,
    revisions: CheckpointRevisions,
  ): List<String>? {
    val workingTreePaths = runLoop.collaborators.outputVerificationContinued4.checkpointOwnedPaths(
      runLoop,
      run,
      baselineOwnedPaths,
    ) ?: return null
    val committedPaths = revisions.base?.let { base ->
      runLoop.gitOperations.runtimePhaseChangedPathsBetweenCommits(run.request.repoRoot, base, revisions.head)
        .takeIf { it.ok }
        ?.value
        ?.let(FeatureTaskRuntimePhaseSafetyPolicy::lineSeparatedPaths)
        ?: return null
    }.orEmpty()
    // Before a checkpoint has decided ownership the working tree is the only listing there is, so it
    // bootstraps the scope. Once a checkpoint has decided, that decision bounds the scope — it already
    // absorbed what the writing phases wrote, so nothing of this run's work is dropped, and ambient
    // dirt can no longer shift the digest a consumer compares against.
    val durableInventory = persistedOwnedPaths.orEmpty().filter(String::isNotBlank)
    val discovered = if (runLoop.session.checkpointOwnershipDecided && durableInventory.isNotEmpty()) {
      durableInventory
    } else {
      (durableInventory + workingTreePaths).distinct()
    }
    val inventory = reconcileCheckpointPathInventory(
      repoRoot = run.request.repoRoot,
      issueKey = run.request.issueKey,
      specReference = run.request.runInvariants.specReference,
      paths = (discovered + committedPaths).distinct(),
    ).sorted()
    return inventory.takeIf {
      runLoop.recorder.recordWorkflowOwnedPaths(
        run.request.workflowId,
        inventory,
        run.request.dbPathOverride,
      )
    }
  }
}

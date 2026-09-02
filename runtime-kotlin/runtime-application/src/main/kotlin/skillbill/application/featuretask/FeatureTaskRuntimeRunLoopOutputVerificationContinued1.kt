package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.reviewevidence.FeatureTaskRuntimeSharedReviewEvidenceResolved
import skillbill.application.reviewevidence.FeatureTaskRuntimeSharedReviewEvidenceResolver
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_KIND_NO_PROGRESS
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgressDecision
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.UNPROVEN_REPOSITORY_FINGERPRINT
import skillbill.workflow.taskruntime.model.detectAuditRepairNonProgress

@Inject
class FeatureTaskRuntimeRunLoopOutputVerificationContinued1 {
  internal fun resolveSharedReviewEvidence(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    checkpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
  ): FeatureTaskRuntimeSharedReviewEvidenceResolved? {
    val declared = run.declaration.projectionDeclarations.any {
      it.sourceRef == FeatureTaskRuntimeHandoffSourceRef.SharedReviewEvidence
    }
    if (!declared) return null
    return FeatureTaskRuntimeSharedReviewEvidenceResolver(
      runLoop.phaseGates.sharedEvidenceResolver,
      runLoop.phaseGates.diffResolver,
    ).resolve(run.request.repoRoot, run.request.workflowId, checkpoint, run.phaseId)
  }

  /**
   * Resolves a repository checkpoint only when some declaration actually needs one, reusing the same
   * `WorkflowGitOperations` fingerprint the audit-repair path already depends on. No new git port is
   * introduced and the domain stays git-agnostic: the checkpoint arrives as a plain value.
   */
  internal fun resolveRepositoryCheckpoint(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
  ): FeatureTaskRuntimeRepositoryCheckpoint? = if (run.declaration.projectionDeclarations.none { projection ->
      projection.checkpointPolicy != FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED
    }
  ) {
    null
  } else {
    runLoop.collaborators.outputVerificationContinued3.buildRepositoryCheckpoint(runLoop, run)
  }

  internal fun completedPhaseRepositoryFingerprint(runLoop: FeatureTaskRuntimeRunLoop, run: PhaseRun) = if (
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT ||
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX
  ) {
    runLoop.gitOperations.repositoryFingerprint(run.request.repoRoot)
  } else {
    null
  }

  internal fun auditGapProgressPause(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    outputMap: Map<String, Any?>,
    repositoryFingerprint: String?,
    auditOutputArtifact: String,
  ): FeatureTaskRuntimeAuditGapPause? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) return null
    if (auditOutputArtifact.isBlank()) return null
    val verdict = FeatureTaskRuntimeOutputVerification.verdictFor(run.phaseId, outputMap)
    val currentHasGaps = verdict == FeatureTaskRuntimeVerdict.GAPS_FOUND
    val previous = runLoop.recorder.loadAuditGapProgress(runLoop.request.workflowId, runLoop.request.dbPathOverride)
    val decision = if (previous == null || !currentHasGaps) {
      FeatureTaskRuntimeAuditRepairProgressDecision(blocked = false, reason = null)
    } else {
      detectAuditRepairNonProgress(
        previousHadGaps = previous.criterionRefs.isNotEmpty(),
        currentHasGaps = true,
        previousRepositoryFingerprint = previous.repositoryFingerprint ?: UNPROVEN_REPOSITORY_FINGERPRINT,
        currentRepositoryFingerprint = repositoryFingerprint ?: UNPROVEN_REPOSITORY_FINGERPRINT,
      )
    }
    if (currentHasGaps) {
      runLoop.recorder.persistAuditGapProgress(
        runLoop.request.workflowId,
        FeatureTaskRuntimeAuditGapProgress(
          criterionRefs = setOf(FeatureTaskRuntimeAuditGapProgress.HAD_GAPS_MARKER),
          repositoryFingerprint = repositoryFingerprint,
        ),
        runLoop.request.dbPathOverride,
      )
    } else {
      runLoop.recorder.loadAuditGapPause(runLoop.request.workflowId, runLoop.request.dbPathOverride)?.let { pause ->
        if (!pause.grantConsumed || pause.operatorDecision != null) {
          runLoop.collaborators.driveContinued1.consumeAuditGapRetryGrant(runLoop, pause)
        }
      }
    }
    if (!decision.blocked) return null
    return FeatureTaskRuntimeAuditGapPause(
      pauseKind = AUDIT_GAP_PAUSE_KIND_NO_PROGRESS,
      reason = noProgressPauseReason(requireNotNull(decision.reason)),
      edgeIteration = runLoop.state.edgeIterationCount(FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) + 1,
    )
  }

  fun noProgressPauseReason(decisionReason: String): String =
    "$decisionReason The subtask is runLoop.session.paused for an operator decision: choose retry_fix to allow one " +
      "further remediation attempt, or abandon_subtask to end the subtask."

  internal fun terminalOutputAttempt(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: TerminalOutputAttemptArgs,
  ): AttemptResult {
    val run = args.run
    val iteration = args.iteration
    val reason = args.reason
    val outputMap = args.outputMap
    val normalizedOutput = args.normalizedOutput
    val repairEvidence = args.repairEvidence
    val observability = args.observability
    val fileManifest = args.fileManifest
    val disposition = FeatureTaskRuntimePhaseSafetyPolicy.dispositionForTerminalOutput(run.phaseId, outputMap)
    val operatorTerminalQualityGate =
      !disposition.retryOnResume &&
        (
          run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ||
            run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
          )
    if (operatorTerminalQualityGate) {
      return AttemptResult.settled(
        runLoop.collaborators.phaseAttempts.blockInPhase(
          runLoop,
          PhaseBlockRequest(
            run = run,
            attemptCount = iteration,
            reason = reason,
            observability = runLoop.observability,
            payload = BlockAndPersistPayload(fileManifest = fileManifest),
            failureDisposition = disposition,
          ),
        ),
      )
    }
    return if (
      disposition.retryOnResume &&
      FeatureTaskRuntimePhaseWorkflowDefinition.retriesOnInvalidOutput(run.phaseId)
    ) {
      // A retryable blocked/failed envelope re-enters the semantic fix loop as itself. It is NOT
      // relabelled schema-invalid: it validated, and converting it would both misreport the run and
      // charge the structural-repair budget for a document with nothing structurally wrong.
      AttemptResult.retryableTerminal(reason, fileManifest, disposition)
    } else {
      AttemptResult.settled(
        runLoop.collaborators.phaseAttempts.blockInPhase(
          runLoop,
          PhaseBlockRequest(
            run = run,
            attemptCount = iteration,
            reason = reason,
            observability = runLoop.observability,
            payload = BlockAndPersistPayload(fileManifest = fileManifest),
            failureDisposition = disposition,
          ),
        ),
      )
    }
  }
}

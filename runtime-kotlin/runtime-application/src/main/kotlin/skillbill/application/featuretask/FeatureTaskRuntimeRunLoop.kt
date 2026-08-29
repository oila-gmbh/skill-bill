@file:Suppress("DestructuringDeclarationWithTooManyEntries")
// FixLoopBranchContext is a deliberate parameter object: the fix-loop branch handlers each read the
// whole set, so destructuring it is what keeps them readable rather than a smell to split.

package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.application.workflow.repoRoot
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.goal.model.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import java.nio.file.Path

internal data class FeatureTaskRuntimeRunLoopDependencies(
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val phaseGates: FeatureTaskRuntimePhaseGates,
  val subtaskLauncher: GoalRunnerSubtaskLauncher,
  val phaseSettlementService: FeatureTaskPhaseSettlementService,
)

internal data class FeatureTaskRuntimeRunLoopContext(
  val request: FeatureTaskRuntimeRunRequest,
  val state: FeatureTaskRuntimeRunState,
  val observability: FeatureTaskRuntimeRunObservability,
  val specSource: SpecSource,
  val transitions: FeatureTaskRuntimeTransitionDeclaration,
  val phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>,
)

internal data class LaunchRejectionAttribution(
  val projectionContractId: String,
  val producerIteration: FeatureTaskRuntimeProducerIteration,
)

internal fun resolveLaunchRejectionAttribution(
  declarations: List<PhaseHandoffProjectionDeclaration>,
  projectionName: String,
  currentProducerIteration: (String) -> Int?,
  fallbackProducerIteration: FeatureTaskRuntimeProducerIteration,
): LaunchRejectionAttribution {
  val declaration = declarations.singleOrNull { it.projectionName == projectionName }
    ?: return LaunchRejectionAttribution(
      projectionContractId = "feature_task_runtime.$projectionName",
      producerIteration = fallbackProducerIteration,
    )
  val declaredProducer = declaration.producerIteration
  return LaunchRejectionAttribution(
    projectionContractId = declaration.projectionContractId,
    producerIteration = FeatureTaskRuntimeProducerIteration(
      phaseId = declaredProducer.phaseId,
      iteration = currentProducerIteration(declaredProducer.phaseId) ?: declaredProducer.iteration,
    ),
  )
}

private const val FEATURE_SPEC_ROOT = ".feature-specs"

internal fun isFeatureSpecPathForIssue(path: String, issueKey: String): Boolean {
  val normalized = path.trim().trimEnd('/')
  if (normalized == FEATURE_SPEC_ROOT) return true
  if (!normalized.startsWith("$FEATURE_SPEC_ROOT/")) return false
  val issueDirectory = normalized.removePrefix("$FEATURE_SPEC_ROOT/").substringBefore('/')
  val key = issueKey.trim()
  return issueDirectory == key || issueDirectory.startsWith("$key-")
}

internal fun reconcileCheckpointPathInventory(
  repoRoot: Path,
  issueKey: String,
  specReference: String,
  paths: List<String>,
): List<String> {
  val specPath = Path.of(specReference)
    .let { path -> if (path.isAbsolute) repoRoot.relativize(path) else path }
    .normalize()
    .toString()
  return paths.filterNot { path ->
    path == specPath || isFeatureSpecPathForIssue(path, issueKey)
  }.distinct()
}

// A reservation at or below the completed-review-output count is a stale latch from the pass that
// already produced a result: re-entry must report the next ordinal, not replay pass one forever.
internal fun resolveReviewPassNumber(reservedPassNumber: Int?, completedReviewPassCount: Int): Int {
  reservedPassNumber?.let { pass ->
    require(pass == 1) { "Review reservation allows only pass 1, was $pass." }
  }
  require(completedReviewPassCount <= 1) {
    "Review completed-pass count cannot exceed one, was $completedReviewPassCount."
  }
  return 1
}

@Suppress("LargeClass", "LongMethod", "LongParameterList", "TooManyFunctions")
internal class FeatureTaskRuntimeRunLoop(
  internal val dependencies: FeatureTaskRuntimeRunLoopDependencies,
  context: FeatureTaskRuntimeRunLoopContext,
  internal val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  internal val request = context.request
  internal val state = context.state
  internal val observability = context.observability
  internal val specSource = context.specSource
  internal val transitions = context.transitions
  internal val phaseTokenAccumulator = context.phaseTokenAccumulator
  internal val recorder get() = dependencies.recorder
  internal val goalContinuationRecorder get() = dependencies.goalContinuationRecorder
  internal val outputValidator get() = dependencies.outputValidator
  internal val phaseGates get() = dependencies.phaseGates
  internal val subtaskLauncher get() = dependencies.subtaskLauncher
  internal val phaseSettlementService get() = dependencies.phaseSettlementService
  internal val branchSetupRunner get() = phaseGates.branchSetupRunner
  internal val planningStopper get() = phaseGates.planningStopper
  internal val gitOperations get() = phaseGates.gitOperations
  internal val planningProjectionValidator get() = phaseGates.planningProjectionValidator
  internal val buildReceiptValidator get() = phaseGates.buildReceiptValidator
  internal val validationGateCoordinator get() = phaseGates.validationGateCoordinator
  internal val buildGateCoordinator get() = phaseGates.buildGateCoordinator

  // Content identity of every dirty path the moment a phase stopped writing, keyed by phase. The
  // checkpoint compares against it to tell this run's own work from an edit that landed beside it.
  internal val phaseContentIdentities = mutableMapOf<String, Map<String, String>>()

  internal var resolvedBranch: String? = null

  // Set once a checkpoint has decided ownership in this process. Until then the durable inventory is
  // only a seed and the working tree still bootstraps the scope; afterwards the checkpoint decision is
  // authoritative and ambient dirt must not widen it.
  internal var checkpointOwnershipDecided: Boolean = false
  internal var blocked: FeatureTaskRuntimeRunReport.Blocked? = null
  internal var paused: FeatureTaskRuntimeRunReport.Paused? = null
  internal var auditGapRetryResumePending: Boolean = false
  internal var decomposed: FeatureTaskRuntimeRunReport.Decomposed? = null
  internal val operatorBlockRetry: FeatureTaskRuntimeOperatorBlockRetry? = recorder
    .loadOperatorBlockRetry(request.workflowId, request.dbPathOverride)
    ?.takeIf { retry ->
      state.recordFor(retry.phaseId)?.status.let { status -> status == null || status == "pending" }
    }
  internal var operatorBlockRetryCompleted: Boolean = false

  internal var pendingReentry: PendingReentry? = resumedReentry()
  internal var activeReentry: PendingReentry? = pendingReentry

  internal val goalContinuationManifestCommitSha: String? = null

  // SKILL-140: set when a phase launch quarantined an upstream record and requested regeneration, so
  // advance() settles the consumer with the RECORD_REJECTED verdict rather than a normal completion.
  internal var recordRejectionSettlementPending: Boolean = false

  fun drive() {
    invalidateReviewGenerationIfNeeded()
    loadMigratedAuditGapPause()?.let { pause ->
      if (resolveAuditGapPauseDriveAction(pause) == AuditGapDriveAction.Stop) return
    }
    if (!validateAuditGapResumeOrBlock()) return
    runPhaseDriveLoop()
  }

  internal fun advance(phaseId: String): PhaseSettlement {
    phaseEntryBlockReason(phaseId)?.let { reason ->
      blockAt(phaseId, reason)
      return PhaseSettlement.stop()
    }
    if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW && isGoalContinuationRun(request)) {
      val carriedForward = carriedForwardGoalReviewSettlement()
      if (carriedForward != null) {
        return carriedForward
      }
    }
    if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT && auditGapRetryResumePending) {
      auditGapRetryResumePending = false
      val carried = settleCarriedForwardAuditGapAudit()
      if (carried != null) return carried
    }
    val reason = advancePhaseReason(phaseId)
    return settleAdvanceOutcome(phaseId, reason)
  }

  // Every reason the phase cannot be entered, evaluated in order and short-circuiting: the declared
  // ordering gate, then the resume cap guard, then the goal review-pass reconciliation.
  fun report(): FeatureTaskRuntimeRunReport {
    val branch = resolvedBranch
      ?: recorder.loadResolvedBranch(request.workflowId, request.dbPathOverride)?.branch
    return decomposed ?: paused?.let { report ->
      if (report.resolvedBranch == null && branch != null) report.copy(resolvedBranch = branch) else report
    } ?: blocked?.let { report ->
      if (report.resolvedBranch == null && branch != null) report.copy(resolvedBranch = branch) else report
    } ?: FeatureTaskRuntimeRunReport.Completed(
      issueKey = request.issueKey,
      workflowId = request.workflowId,
      featureSize = request.runInvariants.featureSize.name,
      completedPhaseIds = state.completedPhaseIds(),
      resolvedBranch = branch,
    )
  }

  internal fun applyOperatorDecision(decision: GoalSubtaskOperatorDecision): String? {
    val auditGapPause = recorder.loadAuditGapPause(request.workflowId, request.dbPathOverride)
    if (auditGapPause != null) {
      return applyAuditGapPauseDecision(auditGapPause, decision)
    }
    return "Operator decisions over review remediation are removed; " +
      "the run advances to validate after one implement_fix round."
  }

  /**
   * Applies an operator decision to a durable audit-gap pause without any review state. `retry_fix`
   * sets the single-use grant (in-session flag plus the durable decision); `abandon_subtask` records
   * the decision for the abandon path on resume; `accept_and_advance` is rejected for this pause class.
   */
}

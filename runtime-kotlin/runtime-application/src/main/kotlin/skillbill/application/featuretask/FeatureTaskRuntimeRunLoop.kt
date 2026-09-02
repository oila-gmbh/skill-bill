package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.goal.model.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import java.nio.file.Path
import java.time.Clock

internal data class FeatureTaskRuntimeRunLoopDependencies(
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val phaseGates: FeatureTaskRuntimePhaseGates,
  val subtaskLauncher: GoalRunnerSubtaskLauncher,
  val phaseSettlementService: FeatureTaskPhaseSettlementService,
  val activityStampWriter: AgentActivityStampWriter,
  val clock: Clock,
  val collaborators: FeatureTaskRuntimeRunLoopCollaborators,
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

fun isFeatureSpecPathForIssue(path: String, issueKey: String): Boolean {
  val normalized = path.trim().trimEnd('/')
  if (normalized == FEATURE_SPEC_ROOT) return true
  if (!normalized.startsWith("$FEATURE_SPEC_ROOT/")) return false
  val issueDirectory = normalized.removePrefix("$FEATURE_SPEC_ROOT/").substringBefore('/')
  val key = issueKey.trim()
  return issueDirectory == key || issueDirectory.startsWith("$key-")
}

fun reconcileCheckpointPathInventory(
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
fun resolveReviewPassNumber(reservedPassNumber: Int?, completedReviewPassCount: Int): Int {
  reservedPassNumber?.let { pass ->
    require(pass == 1) { "Review reservation allows only pass 1, was $pass." }
  }
  require(completedReviewPassCount <= 1) {
    "Review completed-pass count cannot exceed one, was $completedReviewPassCount."
  }
  return 1
}

class FeatureTaskRuntimeRunLoop internal constructor(
  internal val dependencies: FeatureTaskRuntimeRunLoopDependencies,
  context: FeatureTaskRuntimeRunLoopContext,
  val diagnostics: RuntimeDiagnostics,
) {
  val collaborators get() = dependencies.collaborators
  val request = context.request
  val state = context.state
  val observability = context.observability
  val specSource = context.specSource
  val transitions = context.transitions
  val phaseTokenAccumulator = context.phaseTokenAccumulator
  val recorder get() = dependencies.recorder
  val goalContinuationRecorder get() = dependencies.goalContinuationRecorder
  val outputValidator get() = dependencies.outputValidator
  val phaseGates get() = dependencies.phaseGates
  val subtaskLauncher get() = dependencies.subtaskLauncher
  val phaseSettlementService get() = dependencies.phaseSettlementService
  val activityStampWriter get() = dependencies.activityStampWriter
  val clock get() = dependencies.clock
  val branchSetupRunner get() = phaseGates.branchSetupRunner
  val planningStopper get() = phaseGates.planningStopper
  val gitOperations get() = phaseGates.gitOperations
  val planningProjectionValidator get() = phaseGates.planningProjectionValidator
  val buildReceiptValidator get() = phaseGates.buildReceiptValidator
  val validationGateCoordinator get() = phaseGates.validationGateCoordinator
  val buildGateCoordinator get() = phaseGates.buildGateCoordinator

  internal val session = FeatureTaskRuntimeRunLoopSession(
    operatorBlockRetry = dependencies.recorder
      .loadOperatorBlockRetry(context.request.workflowId, context.request.dbPathOverride)
      ?.takeIf { retry ->
        context.state.recordFor(retry.phaseId)?.status.let { status -> status == null || status == "pending" }
      },
    initialPendingReentry = null,
  )

  init {
    session.pendingReentry = collaborators.drive.resumedReentry(this)
    session.activeReentry = session.pendingReentry
  }

  fun drive() {
    collaborators.driveContinued3.invalidateReviewGenerationIfNeeded(this)
    collaborators.driveContinued3.loadMigratedAuditGapPause(this)?.let { pause ->
      if (collaborators.driveContinued3.resolveAuditGapPauseDriveAction(
          this,
          pause,
        ) == FeatureTaskRuntimeRunLoopDriveContinued3.AuditGapDriveAction.Stop
      ) {
        return
      }
    }
    if (!collaborators.driveContinued3.validateAuditGapResumeOrBlock(this)) return
    collaborators.driveContinued3.runPhaseDriveLoop(this)
  }

  internal fun advance(phaseId: String): PhaseSettlement {
    collaborators.drive.phaseEntryBlockReason(this, phaseId)?.let { reason ->
      collaborators.planningBranch.blockAt(this, phaseId, reason)
      return PhaseSettlement.stop()
    }
    if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW && isGoalContinuationRun(request)) {
      val carriedForward = collaborators.driveContinued2.carriedForwardGoalReviewSettlement(this)
      if (carriedForward != null) {
        return carriedForward
      }
    }
    if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT && session.auditGapRetryResumePending) {
      session.auditGapRetryResumePending = false
      val carried = collaborators.driveContinued1.settleCarriedForwardAuditGapAudit(this)
      if (carried != null) return carried
    }
    val reason = collaborators.driveContinued4.advancePhaseReason(this, phaseId)
    return collaborators.driveContinued4.settleAdvanceOutcome(this, phaseId, reason)
  }

  // Every reason the phase cannot be entered, evaluated in order and short-circuiting: the declared
  // ordering gate, then the resume cap guard, then the goal review-pass reconciliation.
  fun report(): FeatureTaskRuntimeRunReport {
    val branch = session.resolvedBranch
      ?: recorder.loadResolvedBranch(request.workflowId, request.dbPathOverride)?.branch
    return session.decomposed ?: session.paused?.let { report ->
      if (report.resolvedBranch == null && branch != null) report.copy(resolvedBranch = branch) else report
    } ?: session.blocked?.let { report ->
      if (report.resolvedBranch == null && branch != null) report.copy(resolvedBranch = branch) else report
    } ?: FeatureTaskRuntimeRunReport.Completed(
      issueKey = request.issueKey,
      workflowId = request.workflowId,
      featureSize = request.runInvariants.featureSize.name,
      completedPhaseIds = state.completedPhaseIds(),
      resolvedBranch = branch,
    )
  }

  fun applyOperatorDecision(decision: GoalSubtaskOperatorDecision): String? {
    val auditGapPause = recorder.loadAuditGapPause(request.workflowId, request.dbPathOverride)
    if (auditGapPause != null) {
      return collaborators.planningBranch.applyAuditGapPauseDecision(this, auditGapPause, decision)
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

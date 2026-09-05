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
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import java.nio.file.Path
import java.time.Clock

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
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val phaseGates: FeatureTaskRuntimePhaseGates,
  val subtaskLauncher: GoalRunnerSubtaskLauncher,
  val phaseSettlementService: FeatureTaskPhaseSettlementService,
  val activityStampWriter: AgentActivityStampWriter,
  val clock: Clock,
  context: FeatureTaskRuntimeRunLoopContext,
  val diagnostics: RuntimeDiagnostics,
) {
  val request = context.request
  val state = context.state
  val observability = context.observability
  val specSource = context.specSource
  val transitions = context.transitions
  val phaseTokenAccumulator = context.phaseTokenAccumulator
  val branchSetupRunner get() = phaseGates.branchSetupRunner
  val planningStopper get() = phaseGates.planningStopper
  val gitOperations get() = phaseGates.gitOperations
  val planningProjectionValidator get() = phaseGates.planningProjectionValidator
  val buildReceiptValidator get() = phaseGates.buildReceiptValidator
  val validationGateCoordinator get() = phaseGates.validationGateCoordinator
  val buildGateCoordinator get() = phaseGates.buildGateCoordinator

  internal val session = FeatureTaskRuntimeRunLoopSession(
    operatorBlockRetry = recorder
      .loadOperatorBlockRetry(request.workflowId, request.dbPathOverride)
      ?.takeIf { retry ->
        state.recordFor(retry.phaseId)?.status.let { status -> status == null || status == "pending" }
      },
    initialPendingReentry = null,
  )

  init {
    session.pendingReentry = FeatureTaskRuntimeRunLoopDrive.resumedReentry(this)
    session.activeReentry = session.pendingReentry
  }

  fun drive() {
    FeatureTaskRuntimeRunLoopDrive.invalidateReviewGenerationIfNeeded(this)
    FeatureTaskRuntimeRunLoopDrive.loadMigratedAuditGapPause(this)?.let { pause ->
      if (FeatureTaskRuntimeRunLoopDrive.resolveAuditGapPauseDriveAction(
          this,
          pause,
        ) == FeatureTaskRuntimeRunLoopDrive.AuditGapDriveAction.Stop
      ) {
        return
      }
    }
    if (!FeatureTaskRuntimeRunLoopDrive.validateAuditGapResumeOrBlock(this)) return
    FeatureTaskRuntimeRunLoopDrive.runPhaseDriveLoop(this)
  }

  internal fun advance(phaseId: String): PhaseSettlement {
    FeatureTaskRuntimeRunLoopDrive.phaseEntryBlockReason(this, phaseId)?.let { reason ->
      FeatureTaskRuntimeRunLoopPlanningBranch.blockAt(this, phaseId, reason)
      return PhaseSettlement.stop()
    }
    if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW && isGoalContinuationRun(request)) {
      val carriedForward = FeatureTaskRuntimeRunLoopDrive.carriedForwardGoalReviewSettlement(this)
      if (carriedForward != null) {
        return carriedForward
      }
    }
    if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT && session.auditGapRetryResumePending) {
      session.auditGapRetryResumePending = false
      val carried = FeatureTaskRuntimeRunLoopDrive.settleCarriedForwardAuditGapAudit(this)
      if (carried != null) return carried
    }
    val reason = FeatureTaskRuntimeRunLoopDrive.advancePhaseReason(this, phaseId)
    return FeatureTaskRuntimeRunLoopDrive.settleAdvanceOutcome(this, phaseId, reason)
  }

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
      return FeatureTaskRuntimeRunLoopPlanningBranch.applyAuditGapPauseDecision(this, auditGapPause, decision)
    }
    return "Operator decisions over review remediation are removed; " +
      "the run advances to validate after one implement_fix round."
  }
}

internal class FeatureTaskRuntimeRunLoopSession(
  internal val operatorBlockRetry: FeatureTaskRuntimeOperatorBlockRetry?,
  initialPendingReentry: PendingReentry?,
) {
  internal val phaseContentIdentities = mutableMapOf<String, Map<String, String>>()
  var resolvedBranch: String? = null
  var checkpointOwnershipDecided: Boolean = false
  var blocked: FeatureTaskRuntimeRunReport.Blocked? = null
  var paused: FeatureTaskRuntimeRunReport.Paused? = null
  var auditGapRetryResumePending: Boolean = false
  var decomposed: FeatureTaskRuntimeRunReport.Decomposed? = null
  var operatorBlockRetryCompleted: Boolean = false
  var pendingReentry: PendingReentry? = initialPendingReentry
  var activeReentry: PendingReentry? = initialPendingReentry
  var recordRejectionSettlementPending: Boolean = false
}

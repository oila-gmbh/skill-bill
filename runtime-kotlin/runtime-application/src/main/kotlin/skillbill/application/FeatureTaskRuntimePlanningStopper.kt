package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.FeatureTaskRuntimePlanningStopDecision
import skillbill.application.model.FeatureTaskRuntimeRunEvent
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.error.SkillBillRuntimeException
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposePlanOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.featureTaskRuntimeDecomposePlanOutcomeOrNull
import java.io.IOException

/**
 * Determines whether the plan phase terminates the run at a planning-stage decompose stop. The
 * determination is derived purely from the persisted PLAN output so it is identical whether the
 * plan phase just ran or PLAN is already durably complete on resume: a decompose outcome must
 * terminate at planning and never advance to implement (AC2). A malformed decompose package yields
 * a Blocked decision (a diagnosable terminal block) rather than an uncaught exception.
 */
@Inject
class FeatureTaskRuntimePlanningStopper(
  private val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  private val decompositionPlanner: FeatureTaskRuntimeDecompositionPlanner,
  private val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder,
) {
  /**
   * Resolves the plan-phase stop decision from the persisted PLAN output. Goal-continuation runs
   * always [Proceed] (AC5). On resume, a previously recorded decompose terminal is reconstructed
   * idempotently (no duplicate spec/manifest write). A first-seen valid decompose outcome completes
   * the stop (write + record + emit). A malformed decompose package blocks loudly.
   */
  fun resolve(
    request: FeatureTaskRuntimeRunRequest,
    completedOutput: FeatureTaskRuntimePhaseOutput,
    completedPhaseIds: List<String>,
    resolvedBranch: String?,
  ): FeatureTaskRuntimePlanningStopDecision {
    if (isGoalContinuationRun(request)) {
      return FeatureTaskRuntimePlanningStopDecision.Proceed
    }
    // A decompose terminal already durably recorded on a prior run is reconstructed without
    // rewriting the specs/manifest, so a crash after PLAN completed but before the terminal was
    // observed re-derives the same Decomposed report rather than advancing to implement.
    val recordedTerminal = decomposeTerminalRecorder.loadDecomposeTerminal(request.workflowId, request.dbPathOverride)
    return if (recordedTerminal != null) {
      FeatureTaskRuntimePlanningStopDecision.Decomposed(
        recordedTerminal.toRunReport(request, completedPhaseIds, resolvedBranch),
      )
    } else {
      resolveFreshPlanOutput(request, completedOutput, completedPhaseIds, resolvedBranch)
    }
  }

  private fun resolveFreshPlanOutput(
    request: FeatureTaskRuntimeRunRequest,
    completedOutput: FeatureTaskRuntimePhaseOutput,
    completedPhaseIds: List<String>,
    resolvedBranch: String?,
  ): FeatureTaskRuntimePlanningStopDecision {
    return try {
      resolveFromPlanOutput(request, completedOutput, completedPhaseIds, resolvedBranch)
    } catch (error: SkillBillRuntimeException) {
      // Covers decoder schema errors (InvalidFeatureTaskRuntimePhaseOutputSchemaError,
      // InvalidWorkflowStateSchemaError), decomposition-manifest schema errors
      // (InvalidDecompositionManifestSchemaError), and writer business-rule rejections
      // (InvalidFeatureSpecPreparationRequestError, FeatureSpecPreparationModeConflictError) — all
      // share this base. Any malformed/invalid decomposition package blocks at planning.
      FeatureTaskRuntimePlanningStopDecision.Blocked(malformedDecomposeReason(error.message.orEmpty()))
    } catch (error: IllegalArgumentException) {
      FeatureTaskRuntimePlanningStopDecision.Blocked(malformedDecomposeReason(error.message.orEmpty()))
    } catch (error: IOException) {
      FeatureTaskRuntimePlanningStopDecision.Blocked(malformedDecomposeReason(error.message.orEmpty()))
    }
  }

  private fun resolveFromPlanOutput(
    request: FeatureTaskRuntimeRunRequest,
    completedOutput: FeatureTaskRuntimePhaseOutput,
    completedPhaseIds: List<String>,
    resolvedBranch: String?,
  ): FeatureTaskRuntimePlanningStopDecision {
    val parsed = outputValidator.validateAndReadPhaseOutput(
      completedOutput.payload,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
    )
    val outcome = featureTaskRuntimeDecomposePlanOutcomeOrNull(parsed)
      ?: return FeatureTaskRuntimePlanningStopDecision.Proceed
    val terminal = writeDecompositionTerminal(request, outcome)
    decomposeTerminalRecorder.recordDecomposeTerminal(request.workflowId, terminal, request.dbPathOverride)
    emitDecomposedAtPlanning(request, terminal)
    return FeatureTaskRuntimePlanningStopDecision.Decomposed(
      terminal.toRunReport(request, completedPhaseIds, resolvedBranch),
    )
  }

  private fun writeDecompositionTerminal(
    request: FeatureTaskRuntimeRunRequest,
    outcome: FeatureTaskRuntimeDecomposePlanOutcome,
  ): FeatureTaskRuntimeDecomposeTerminal {
    val writeResult = decompositionPlanner.writeDecomposition(
      repoRoot = request.repoRoot,
      issueKey = request.issueKey,
      runInvariants = request.runInvariants,
      outcome = outcome,
    )
    return FeatureTaskRuntimeDecomposeTerminal(
      reason = outcome.reason,
      parentSpecPath = writeResult.parentSpecPath,
      decompositionManifestPath = requireNotNull(writeResult.decompositionManifestPath) {
        "Decomposed feature-spec write result must include a decomposition manifest path."
      },
      subtaskSpecPaths = writeResult.subtaskSpecPaths,
    )
  }

  private fun emitDecomposedAtPlanning(
    request: FeatureTaskRuntimeRunRequest,
    terminal: FeatureTaskRuntimeDecomposeTerminal,
  ) {
    request.eventSink.emit(
      FeatureTaskRuntimeRunEvent.DecomposedAtPlanning(
        workflowId = request.workflowId,
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        reason = terminal.reason,
        subtaskCount = terminal.subtaskCount,
        parentSpecPath = terminal.parentSpecPath,
        decompositionManifestPath = terminal.decompositionManifestPath,
      ),
    )
  }

  private fun FeatureTaskRuntimeDecomposeTerminal.toRunReport(
    request: FeatureTaskRuntimeRunRequest,
    completedPhaseIds: List<String>,
    resolvedBranch: String?,
  ): FeatureTaskRuntimeRunReport.Decomposed = FeatureTaskRuntimeRunReport.Decomposed(
    issueKey = request.issueKey,
    workflowId = request.workflowId,
    featureSize = request.runInvariants.featureSize.name,
    reason = reason,
    completedPhaseIds = completedPhaseIds,
    parentSpecPath = parentSpecPath,
    decompositionManifestPath = decompositionManifestPath,
    subtaskSpecPaths = subtaskSpecPaths,
    resolvedBranch = resolvedBranch,
  )

  private fun malformedDecomposeReason(detail: String): String {
    val bounded = detail.takeIf(String::isNotBlank)?.let {
      if (it.length <= MALFORMED_DETAIL_MAX_CHARS) it else it.take(MALFORMED_DETAIL_MAX_CHARS) + "… [truncated]"
    }
    return "Plan declared mode 'decompose' but emitted a malformed decomposition package; the runtime " +
      "blocks at planning rather than crashing or advancing to implement." +
      (bounded?.let { " Schema problem: $it" } ?: "")
  }

  private companion object {
    const val MALFORMED_DETAIL_MAX_CHARS = 500
  }
}

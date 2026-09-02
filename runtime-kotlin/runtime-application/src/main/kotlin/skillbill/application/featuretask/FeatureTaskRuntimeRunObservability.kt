package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunEvent
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.config.model.PhaseModelDirective
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import kotlin.coroutines.cancellation.CancellationException

internal enum class FeatureTaskRuntimeContinuationKind(val wireValue: String) {
  IMPLEMENTATION_CONTINUATION("implementation_continuation"),
  SCHEMA_CORRECTION("schema_correction"),
  PROCESS_RETRY("process_retry"),
  CRASH_RESUME("crash_resume"),
  VERIFIER_REENTRY("verifier_reentry"),
  ITEM_COVERAGE("item_coverage"),
  VERIFICATION_BODY_DELIVERY("verification_body_delivery"),
  ;

  companion object {
    const val LEDGER_DETAIL_PREFIX: String = "continuation:"

    fun fromLedgerDetail(detail: String?): FeatureTaskRuntimeContinuationKind? = detail
      ?.takeIf { it.startsWith(LEDGER_DETAIL_PREFIX) }
      ?.removePrefix(LEDGER_DETAIL_PREFIX)
      ?.substringBefore(' ')
      ?.let { value -> entries.firstOrNull { it.wireValue == value } }
  }
}

internal data class FeatureTaskRuntimePhaseStartReentry(
  val resumed: Boolean,
  val startKind: FeatureTaskRuntimeContinuationKind?,
) {
  companion object {
    val FIRST_VISIT: FeatureTaskRuntimePhaseStartReentry =
      FeatureTaskRuntimePhaseStartReentry(resumed = false, startKind = null)
  }
}

fun emitFeatureTaskRuntimeEventSafely(diagnostics: RuntimeDiagnostics, seam: String, emit: () -> Unit) {
  runCatching { emit() }
    .exceptionOrNull()
    ?.let { error ->
      if (error is CancellationException) throw error
      runCatching {
        diagnostics.warning(
          "Feature-task-runtime $seam failed; the run is unaffected.",
          error,
        )
      }
    }
}

class FeatureTaskRuntimeRunObservability(
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val request: FeatureTaskRuntimeRunRequest,
  val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  fun branchResolved(phaseId: String, branch: String, created: Boolean, reused: Boolean) {
    emitSafely(
      FeatureTaskRuntimeRunEvent.BranchResolved(
        workflowId = request.workflowId,
        phaseId = phaseId,
        branch = branch,
        created = created,
        reused = reused,
      ),
    )
  }

  fun branchSetupBlocked(phaseId: String, resolvedAgentId: String, blockedReason: String) {
    emitSafely(
      FeatureTaskRuntimeRunEvent.BranchSetupBlocked(
        workflowId = request.workflowId,
        phaseId = phaseId,
        blockedReason = blockedReason,
      ),
    )
    appendLedger(
      FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = request.workflowId,
        action = FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
        phaseId = phaseId,
        attemptCount = 1,
        resolvedAgentId = resolvedAgentId,
        blockedReason = blockedReason,
      ),
    )
  }

  internal fun started(
    phaseId: String,
    resolvedAgentId: String,
    attemptCount: Int,
    directive: PhaseModelDirective?,
    reentry: FeatureTaskRuntimePhaseStartReentry = FeatureTaskRuntimePhaseStartReentry.FIRST_VISIT,
  ) {
    val resumed = reentry.resumed
    val startKind = reentry.startKind
    emitSafely(
      FeatureTaskRuntimeRunEvent.PhaseStarted(
        workflowId = request.workflowId,
        phaseId = phaseId,
        resolvedAgentId = resolvedAgentId,
        attemptCount = attemptCount,
        resumed = resumed,
        model = directive?.model,
        effort = directive?.effort,
        continuationKind = startKind?.wireValue,
      ),
    )
    appendLedger(
      FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = request.workflowId,
        action = if (resumed) {
          FeatureTaskRuntimePhaseLedgerAction.RESUME
        } else {
          FeatureTaskRuntimePhaseLedgerAction.START
        },
        phaseId = phaseId,
        attemptCount = attemptCount,
        resolvedAgentId = resolvedAgentId,
        blockedReason = startKind?.let { "${FeatureTaskRuntimeContinuationKind.LEDGER_DETAIL_PREFIX}${it.wireValue}" },
      ),
    )
  }

  fun completed(phaseId: String, resolvedAgentId: String, attemptCount: Int) {
    completedEvent(phaseId, resolvedAgentId, attemptCount)
    appendLedger(
      FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = request.workflowId,
        action = FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
        phaseId = phaseId,
        attemptCount = attemptCount,
        resolvedAgentId = resolvedAgentId,
      ),
    )
  }

  fun validationGateProgress() = Unit
}

internal fun featureTaskRuntimeStartContinuationKind(
  crashResumed: Boolean,
  verifierReentry: Boolean,
  attemptCount: Int,
): FeatureTaskRuntimeContinuationKind? = when {
  crashResumed -> FeatureTaskRuntimeContinuationKind.CRASH_RESUME
  verifierReentry -> null
  attemptCount > 1 -> FeatureTaskRuntimeContinuationKind.PROCESS_RETRY
  else -> null
}

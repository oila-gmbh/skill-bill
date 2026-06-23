package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.FeatureTaskRuntimeFinishedRequest
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.model.FeatureTaskRuntimeStartedRequest
import skillbill.application.telemetry.LifecycleTelemetryService
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics

/**
 * Runtime-owned lifecycle telemetry seam for the feature-task-runtime phase loop. The runtime mints
 * and emits the started/finished events from its own per-phase outcomes, never the agent. This is
 * additive: the per-phase records and ledger remain the authoritative observability source.
 *
 * Every emission is failure-isolated here (logged, never swallowed silently): telemetry is additive
 * observability, so a telemetry DB/transaction/serialization fault must never abort or falsely-fail a
 * run. The authoritative per-phase records/ledger own run correctness.
 */
@Inject
class FeatureTaskRuntimeLifecycleTelemetry(
  private val lifecycleTelemetryService: LifecycleTelemetryService,
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  // Returns the started session id, or blank when the isolated started emission failed; a blank id
  // makes the matching finished/finishedError emission a no-op, so a started fault cannot dangle.
  fun started(request: FeatureTaskRuntimeRunRequest): String = isolate("started", "") {
    lifecycleTelemetryService.featureTaskRuntimeStarted(
      FeatureTaskRuntimeStartedRequest(
        featureSize = request.runInvariants.featureSize.name,
        issueKey = request.issueKey,
        featureName = request.runInvariants.specReference,
        sessionId = request.sessionId,
      ),
      dbOverride = request.dbPathOverride,
    )["session_id"]?.toString().orEmpty()
  }

  @Suppress("LongParameterList") // one cohesive finished-telemetry emission; bundling would only hide it
  fun finished(
    telemetrySessionId: String,
    report: FeatureTaskRuntimeRunReport,
    phaseOutcomes: () -> Map<String, String>,
    reviewFixIterationCount: () -> Int,
    auditGapIterationCount: () -> Int,
    dbOverride: String?,
    phaseTokenData: () -> Pair<String?, Int?> = { null to null },
  ) {
    if (telemetrySessionId.isBlank()) {
      return
    }
    isolate("finished", Unit) {
      val (tokenBreakdownJson, totalTokens) = runCatching(phaseTokenData).getOrDefault(null to null)
      lifecycleTelemetryService.featureTaskRuntimeFinished(
        FeatureTaskRuntimeFinishedRequest(
          sessionId = telemetrySessionId,
          completionStatus = completionStatusOf(report),
          completedPhaseIds = completedPhaseIdsOf(report),
          phaseOutcomes = phaseOutcomes(),
          lastIncompletePhase = (report as? FeatureTaskRuntimeRunReport.Blocked)?.lastIncompletePhase.orEmpty(),
          blockedReason = (report as? FeatureTaskRuntimeRunReport.Blocked)?.blockedReason.orEmpty(),
          resolvedBranch = report.resolvedBranch.orEmpty(),
          reviewFixIterationCount = runCatching(reviewFixIterationCount).getOrDefault(0),
          auditGapIterationCount = runCatching(auditGapIterationCount).getOrDefault(0),
          estimatedPhaseTokenBreakdownJson = tokenBreakdownJson,
          estimatedTotalTokens = totalTokens,
        ),
        dbOverride = dbOverride,
      )
    }
  }

  // Closes a started session that ended on an exception escaping the run loop, emitting the contract's
  // "error" completion bucket. Phase fields are best-effort from the per-phase records available at the
  // point of failure; completedPhaseIds is sourced from records the runtime durably marked completed.
  // The emission (including resolving phaseOutcomes) is failure-isolated so it can never mask or
  // replace the original run exception, which always remains the one that propagates.
  @Suppress("LongParameterList") // parallel structure to finished(); bundling would only hide it
  fun finishedError(
    telemetrySessionId: String,
    phaseOutcomes: () -> Map<String, String>,
    reviewFixIterationCount: () -> Int,
    auditGapIterationCount: () -> Int,
    dbOverride: String?,
    phaseTokenData: () -> Pair<String?, Int?> = { null to null },
  ) {
    if (telemetrySessionId.isBlank()) {
      return
    }
    isolate("finishedError", Unit) {
      val outcomes = runCatching(phaseOutcomes)
        .onFailure { error ->
          diagnostics.warning(
            "Feature-task-runtime lifecycle telemetry error outcome loading failed; " +
              "emitting terminal error without outcomes.",
            error,
          )
        }
        .getOrDefault(emptyMap())
      val (tokenBreakdownJson, totalTokens) = runCatching(phaseTokenData).getOrDefault(null to null)
      lifecycleTelemetryService.featureTaskRuntimeFinished(
        FeatureTaskRuntimeFinishedRequest(
          sessionId = telemetrySessionId,
          completionStatus = "error",
          completedPhaseIds = outcomes.filterValues { it == "completed" }.keys.toList(),
          phaseOutcomes = outcomes,
          lastIncompletePhase = outcomes.entries.firstOrNull { it.value != "completed" }?.key.orEmpty(),
          blockedReason = "",
          resolvedBranch = "",
          reviewFixIterationCount = runCatching(reviewFixIterationCount).getOrDefault(0),
          auditGapIterationCount = runCatching(auditGapIterationCount).getOrDefault(0),
          estimatedPhaseTokenBreakdownJson = tokenBreakdownJson,
          estimatedTotalTokens = totalTokens,
        ),
        dbOverride = dbOverride,
      )
    }
  }

  private fun <T> isolate(stage: String, fallback: T, block: () -> T): T = runCatching(block)
    .onFailure { error ->
      diagnostics.warning(
        "Feature-task-runtime lifecycle telemetry $stage emission failed; the run is unaffected.",
        error,
      )
    }
    .getOrDefault(fallback)

  private fun completionStatusOf(report: FeatureTaskRuntimeRunReport): String = when (report) {
    is FeatureTaskRuntimeRunReport.Completed -> "completed"
    is FeatureTaskRuntimeRunReport.Blocked -> "blocked"
    is FeatureTaskRuntimeRunReport.Decomposed -> "decomposed_at_planning"
  }

  private fun completedPhaseIdsOf(report: FeatureTaskRuntimeRunReport): List<String> = when (report) {
    is FeatureTaskRuntimeRunReport.Completed -> report.completedPhaseIds
    is FeatureTaskRuntimeRunReport.Blocked -> report.completedPhaseIds
    is FeatureTaskRuntimeRunReport.Decomposed -> report.completedPhaseIds
  }
}

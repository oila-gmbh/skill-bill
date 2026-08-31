package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimeFinishedTelemetryContext
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.application.telemetry.LifecycleTelemetryService
import skillbill.application.telemetry.model.FeatureTaskRuntimeStartedRequest
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics

@Inject
class FeatureTaskRuntimeLifecycleTelemetry(
  private val lifecycleTelemetryService: LifecycleTelemetryService,
  private val diagnostics: RuntimeDiagnostics,
) {
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

  internal fun finished(report: FeatureTaskRuntimeRunReport, context: FeatureTaskRuntimeFinishedTelemetryContext) {
    if (context.telemetrySessionId.isBlank()) {
      return
    }
    isolate("finished", Unit) {
      emitFeatureTaskRuntimeFinished(
        lifecycleTelemetryService,
        report,
        context,
        completionStatusOf(report),
      )
    }
  }

  internal fun finishedError(context: FeatureTaskRuntimeFinishedTelemetryContext) {
    if (context.telemetrySessionId.isBlank()) {
      return
    }
    isolate("finishedError", Unit) {
      val outcomes = runCatching(context.phaseOutcomes)
        .onFailure { error ->
          diagnostics.warning(
            "Feature-task-runtime lifecycle telemetry error outcome loading failed; " +
              "emitting terminal error without outcomes.",
            error,
          )
        }
        .getOrDefault(emptyMap())
      emitFeatureTaskRuntimeFinishedError(lifecycleTelemetryService, context, outcomes)
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
}

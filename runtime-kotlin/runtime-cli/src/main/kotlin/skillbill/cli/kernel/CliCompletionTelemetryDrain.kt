package skillbill.cli.kernel

import skillbill.application.telemetry.TelemetryService
import skillbill.ports.diagnostics.RuntimeDiagnostics

/**
 * Wall-clock bound for the completion drain. An untimed HTTP request against a blackholed proxy
 * must never hold up process exit, so the drain runs on a daemon thread and is abandoned here.
 */
private const val DRAIN_TIMEOUT_MILLIS = 5_000L

/**
 * Flushes the telemetry outbox at a CLI completion boundary by reusing [TelemetryService.autoSync].
 * Level gating comes free from autoSync's early return; every [Exception] is swallowed so a drain
 * can never change the run's reported outcome or exit code, and nothing reaches stdout or stderr.
 * Abandonment is not silent: each path that gives up on the flush emits a [RuntimeDiagnostics]
 * warning, which is the sanctioned channel for a degradation that must stay off the run's output.
 */
internal fun drainTelemetryOnCompletion(
  telemetryService: TelemetryService,
  dbOverride: String?,
  diagnostics: RuntimeDiagnostics,
) {
  val worker = Thread {
    runCatching { telemetryService.autoSync(dbOverride) }
      .onFailure { error -> diagnostics.warning("telemetry completion drain failed to flush the outbox", error) }
  }
  worker.isDaemon = true
  worker.start()
  try {
    worker.join(DRAIN_TIMEOUT_MILLIS)
    if (worker.isAlive) {
      diagnostics.warning(
        "telemetry completion drain abandoned after ${DRAIN_TIMEOUT_MILLIS}ms; the outbox may not have flushed",
      )
    }
  } catch (interrupted: InterruptedException) {
    diagnostics.warning("telemetry completion drain interrupted before the outbox flushed", interrupted)
    Thread.currentThread().interrupt()
  }
}

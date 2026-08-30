package skillbill.cli.telemetry

import skillbill.application.telemetry.TelemetryService

/**
 * Wall-clock bound for the completion drain. An untimed HTTP request against a blackholed proxy
 * must never hold up process exit, so the drain runs on a daemon thread and is abandoned here.
 */
private const val DRAIN_TIMEOUT_MILLIS = 5_000L

/**
 * Flushes the telemetry outbox at a CLI completion boundary by reusing [TelemetryService.autoSync].
 * Level gating comes free from autoSync's early return; every [Exception] is swallowed so a drain
 * can never change the run's reported outcome or exit code, and nothing reaches stdout or stderr.
 */
internal fun drainTelemetryOnCompletion(telemetryService: TelemetryService, dbOverride: String?) {
  val worker = Thread {
    runCatching { telemetryService.autoSync(dbOverride) }
  }
  worker.isDaemon = true
  worker.start()
  try {
    worker.join(DRAIN_TIMEOUT_MILLIS)
  } catch (interrupted: InterruptedException) {
    Thread.currentThread().interrupt()
  }
}

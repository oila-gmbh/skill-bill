package skillbill.telemetry

import java.nio.file.Path

object TelemetryRuntime {
  fun loadTelemetrySettings(
    materialize: Boolean = false,
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Path.of(System.getProperty("user.home")),
  ): TelemetrySettings = TelemetryConfigRuntime.loadTelemetrySettings(
    materialize = materialize,
    environment = environment,
    userHome = userHome,
  )

  fun telemetryIsEnabled(
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Path.of(System.getProperty("user.home")),
  ): Boolean = TelemetryConfigRuntime.telemetryIsEnabled(environment, userHome)
}

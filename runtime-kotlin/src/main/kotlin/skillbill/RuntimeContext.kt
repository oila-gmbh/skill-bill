package skillbill

import skillbill.telemetry.HttpRequester
import skillbill.telemetry.TelemetryHttpRuntime
import java.nio.file.Path

data class RuntimeContext(
  val dbPathOverride: String? = null,
  val stdinText: String? = null,
  val environment: Map<String, String> = System.getenv(),
  val userHome: Path = Path.of(System.getProperty("user.home")),
  val requester: HttpRequester = TelemetryHttpRuntime.defaultHttpRequester,
)

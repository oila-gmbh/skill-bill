package skillbill.cli.models

import skillbill.RuntimeContext
import skillbill.infrastructure.http.JdkHttpRequester
import skillbill.ports.telemetry.HttpRequester
import java.nio.file.Path

data class CliRuntimeContext(
  val dbPathOverride: String? = null,
  val stdinText: String? = null,
  val environment: Map<String, String> = System.getenv(),
  val userHome: Path = Path.of(System.getProperty("user.home")),
  val requester: HttpRequester = JdkHttpRequester,
) {
  fun toRuntimeContext(): RuntimeContext = RuntimeContext(
    dbPathOverride = dbPathOverride,
    stdinText = stdinText,
    environment = environment,
    userHome = userHome,
    requester = requester,
  )
}

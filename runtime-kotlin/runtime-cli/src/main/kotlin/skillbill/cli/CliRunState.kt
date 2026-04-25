package skillbill.cli

import me.tatarka.inject.annotations.Inject
import java.nio.file.Path

@Inject
class CliRunState {
  var dbOverride: String? = null
  var stdinText: String? = null
  var environment: Map<String, String> = System.getenv()
  var userHome: Path = Path.of(System.getProperty("user.home"))
  var result: CliExecutionResult? = null

  fun complete(payload: Map<String, Any?>, format: CliFormat, exitCode: Int = 0) {
    result = CliExecutionResult(exitCode = exitCode, stdout = CliOutput.emit(payload, format), payload = payload)
  }

  fun completeText(stdout: String, payload: Map<String, Any?>, exitCode: Int = 0) {
    result = CliExecutionResult(exitCode = exitCode, stdout = stdout, payload = payload)
  }
}

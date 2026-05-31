package skillbill.cli

import me.tatarka.inject.annotations.Inject
import java.nio.file.Path

@Inject
class CliRunState {
  var dbOverride: String? = null
  var stdinText: String? = null
  var environment: Map<String, String> = System.getenv()
  var userHome: Path = Path.of(System.getProperty("user.home"))
  var liveStdout: (String) -> Unit = {}
  var liveStderr: (String) -> Unit = {}
  var result: CliExecutionResult? = null
  private var stdinLineIterator: Iterator<String>? = null

  fun complete(payload: Map<String, Any?>, format: CliFormat, exitCode: Int = 0) {
    result = CliExecutionResult(exitCode = exitCode, stdout = CliOutput.emit(payload, format), payload = payload)
  }

  fun completeText(stdout: String, payload: Map<String, Any?>, exitCode: Int = 0) {
    result = CliExecutionResult(exitCode = exitCode, stdout = stdout, payload = payload)
  }

  fun readInputLine(): String? {
    val text = stdinText
    if (text != null) {
      val iterator = stdinLineIterator ?: text.lineSequence().iterator().also { stdinLineIterator = it }
      return if (iterator.hasNext()) iterator.next() else null
    }
    return readlnOrNull()
  }
}

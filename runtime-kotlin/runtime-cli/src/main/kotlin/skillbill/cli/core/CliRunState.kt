package skillbill.cli.core

import skillbill.cli.model.CliExecutionResult
import skillbill.cli.model.CliFormat

class CliRunState(private val stdinText: String?) {
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

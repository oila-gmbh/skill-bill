package skillbill.cli

import me.tatarka.inject.annotations.Inject

@Inject
class CliRunState {
  var dbOverride: String? = null
  var result: CliExecutionResult? = null

  fun complete(payload: Map<String, Any?>, format: CliFormat, exitCode: Int = 0) {
    result = CliExecutionResult(exitCode = exitCode, stdout = CliOutput.emit(payload, format), payload = payload)
  }

  fun completeText(stdout: String, payload: Map<String, Any?>, exitCode: Int = 0) {
    result = CliExecutionResult(exitCode = exitCode, stdout = stdout, payload = payload)
  }
}

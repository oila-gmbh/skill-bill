package skillbill.cli.model

data class CliExecutionResult(
  val exitCode: Int,
  val stdout: String,
  val payload: Map<String, Any?>? = null,
)

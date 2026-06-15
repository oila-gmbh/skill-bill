package skillbill.cli.core

import java.io.IOException
import java.nio.file.Path

data class ExternalCommand(
  val executable: String,
  val arguments: List<String>,
  val environment: Map<String, String>,
  val workingDirectory: Path? = null,
)

data class ExternalCommandResult(
  val exitCode: Int,
  val output: String,
)

fun interface ExternalCommandRunner {
  fun run(command: ExternalCommand): ExternalCommandResult
}

object ProcessExternalCommandRunner : ExternalCommandRunner {
  override fun run(command: ExternalCommand): ExternalCommandResult = try {
    val process = ProcessBuilder(listOf(command.executable) + command.arguments)
      .redirectErrorStream(true)
      .apply {
        command.workingDirectory?.let { directory(it.toFile()) }
        environment().clear()
        environment().putAll(command.environment)
      }
      .start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    ExternalCommandResult(exitCode = exitCode, output = output)
  } catch (error: IOException) {
    ExternalCommandResult(exitCode = 1, output = "Failed to launch installer: ${error.message.orEmpty()}")
  } catch (error: InterruptedException) {
    Thread.currentThread().interrupt()
    ExternalCommandResult(exitCode = 1, output = "Installer interrupted.")
  }
}

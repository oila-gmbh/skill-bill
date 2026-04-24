package skillbill.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parsers.CommandLineParser
import skillbill.di.RuntimeComponent
import skillbill.di.create

object CliRuntime {
  fun run(arguments: List<String>, context: CliRuntimeContext = CliRuntimeContext()): CliExecutionResult {
    val runtimeComponent = RuntimeComponent::class.create(context.toRuntimeContext())
    val cliComponent = CliComponent::class.create(runtimeComponent, CliRunState())
    val rootCommand = cliComponent.rootCommand
    return try {
      CommandLineParser.parseAndRun(rootCommand, arguments) { command -> command.run() }
      cliComponent.runState.result
        ?: CliExecutionResult(exitCode = 0, stdout = rootCommand.getFormattedHelp().orEmpty())
    } catch (error: CliktError) {
      CliExecutionResult(
        exitCode = error.statusCode,
        stdout = rootCommand.getFormattedHelp(error).orEmpty(),
      )
    } catch (error: IllegalArgumentException) {
      CliExecutionResult(
        exitCode = 1,
        stdout = error.message.orEmpty(),
      )
    }
  }
}

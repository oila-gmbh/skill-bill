package skillbill.cli.core

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parsers.CommandLineParser
import skillbill.cli.kernel.CliRunState
import skillbill.cli.model.CliExecutionResult
import skillbill.cli.model.CliRunInputs
import skillbill.cli.model.CliRuntimeContext
import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.error.DatabaseAccessError
import java.nio.file.Path

object CliRuntime {
  fun run(arguments: List<String>, context: CliRuntimeContext = CliRuntimeContext()): CliExecutionResult {
    val rootFlags = RootFlagProbeCommand()
    runCatching { CommandLineParser.parseAndRun(rootFlags, arguments) { } }
    val runtimeComponent = RuntimeComponent::class.create(
      context.toRuntimeContext(
        dbPathOverride = rootFlags.dbOverride ?: context.dbPathOverride,
        userHome = rootFlags.homeOverride?.let(Path::of) ?: context.userHome,
      ),
    )
    val resolved = runtimeComponent.resolvedEnvironmentContext
    val runState = CliRunState(context.stdinText)
    val runInputs = CliRunInputs(
      dbPathOverride = resolved.dbPathOverride,
      stdinText = context.stdinText,
      environment = resolved.environment,
      externalCommandRunner = context.externalCommandRunner,
      userHome = resolved.userHome,
      repositoryRoot = resolved.repositoryRoot,
      repositoryEnclosingRootPort = runtimeComponent.repositoryEnclosingRootPort,
      liveStdout = context.liveStdout,
      liveStderr = context.liveStderr,
    )
    val cliComponent = CliComponent::class.create(runtimeComponent, runState, runInputs)
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
    } catch (error: DatabaseAccessError) {
      CliExecutionResult(
        exitCode = 1,
        stdout = error.message.orEmpty(),
      )
    }
  }
}

private class RootFlagProbeCommand : CliktCommand("skill-bill") {
  val dbOverride by dbPathOverrideOption()
  val homeOverride by userHomeOverrideOption()
  val ignoredTokens by argument().multiple()

  override val treatUnknownOptionsAsArgs: Boolean = true

  init {
    context {
      helpOptionNames = emptySet()
      allowInterspersedArgs = false
    }
  }

  override fun run() = Unit
}

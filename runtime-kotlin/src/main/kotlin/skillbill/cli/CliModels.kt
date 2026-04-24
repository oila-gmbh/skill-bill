package skillbill.cli

typealias CliExecutionResult = skillbill.cli.models.CliExecutionResult
typealias CliRuntimeContext = skillbill.cli.models.CliRuntimeContext
typealias CliFormat = skillbill.cli.models.CliFormat

internal typealias FormatOnlyArgs = skillbill.cli.models.FormatOnlyArgs

internal class ArgumentCursor(arguments: List<String>) {
  private val args = arguments.toList()
  private var index: Int = 0

  fun hasNext(): Boolean = index < args.size

  fun peek(): String {
    require(hasNext()) { "Missing command arguments." }
    return args[index]
  }

  fun take(): String {
    require(hasNext()) { "Missing command arguments." }
    return args[index++]
  }

  fun requireValue(flag: String): String {
    require(hasNext()) { "$flag requires a value." }
    return take()
  }

  fun rejectExtraArguments(commandName: String) {
    require(!hasNext()) {
      "Unexpected arguments for $commandName: ${args.subList(index, args.size).joinToString(" ")}"
    }
  }
}

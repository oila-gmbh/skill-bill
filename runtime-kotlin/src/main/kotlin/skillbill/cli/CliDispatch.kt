package skillbill.cli

internal data class CliInvocation(
  val cursor: ArgumentCursor,
  val context: CliRuntimeContext,
  val dbOverride: String?,
)

internal sealed interface CliCommandNode {
  val name: String
  val aliases: Set<String>

  fun matches(token: String): Boolean = token == name || token in aliases

  fun execute(invocation: CliInvocation): CliExecutionResult
}

internal class CliCommandGroup(
  override val name: String,
  override val aliases: Set<String> = emptySet(),
  private val children: List<CliCommandNode>,
  private val unknownCommandMessage: (String) -> String = { token -> "Unsupported $name command '$token'." },
) : CliCommandNode {
  override fun execute(invocation: CliInvocation): CliExecutionResult = dispatch(invocation.cursor.take(), invocation)

  fun dispatch(token: String, invocation: CliInvocation): CliExecutionResult {
    val child = children.firstOrNull { it.matches(token) }
      ?: throw IllegalArgumentException(unknownCommandMessage(token))
    return child.execute(invocation)
  }
}

internal class CliLeafCommand<Args>(
  override val name: String,
  override val aliases: Set<String> = emptySet(),
  private val parse: (ArgumentCursor) -> Args,
  private val executeCommand: (Args, CliRuntimeContext, String?) -> CliExecutionResult,
) : CliCommandNode {
  override fun execute(invocation: CliInvocation): CliExecutionResult =
    executeCommand(parse(invocation.cursor), invocation.context, invocation.dbOverride)
}

internal fun commandGroup(
  name: String,
  aliases: Set<String> = emptySet(),
  children: List<CliCommandNode>,
  unknownCommandMessage: (String) -> String = { token -> "Unsupported $name command '$token'." },
): CliCommandGroup = CliCommandGroup(name, aliases, children, unknownCommandMessage)

internal fun <Args> leafCommand(
  name: String,
  aliases: Set<String> = emptySet(),
  parse: (ArgumentCursor) -> Args,
  execute: (Args, CliRuntimeContext, String?) -> CliExecutionResult,
): CliCommandNode = CliLeafCommand(name, aliases, parse, execute)

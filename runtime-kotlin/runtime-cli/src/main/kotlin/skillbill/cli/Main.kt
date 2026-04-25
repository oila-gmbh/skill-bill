package skillbill.cli

fun main(args: Array<String>) {
  val stdinText =
    if ("-" in args) {
      generateSequence { readlnOrNull() }.joinToString("\n").takeIf { it.isNotEmpty() }
    } else {
      null
    }
  val result = CliRuntime.run(args.toList(), CliRuntimeContext(stdinText = stdinText))
  print(result.stdout)
  if (result.stdout.isNotEmpty() && !result.stdout.endsWith("\n")) {
    println()
  }
  kotlin.system.exitProcess(result.exitCode)
}

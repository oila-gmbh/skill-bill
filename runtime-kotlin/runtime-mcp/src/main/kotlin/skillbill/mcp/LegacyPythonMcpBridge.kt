package skillbill.mcp

import skillbill.contracts.JsonSupport
import java.nio.file.Path
import kotlin.io.path.exists

object LegacyPythonMcpBridge {
  fun call(toolName: String, arguments: Map<String, Any?>): Map<String, Any?> {
    val process =
      ProcessBuilder(pythonCommand() + listOf("-m", "skill_bill.mcp_tool_bridge", toolName))
        .directory(repoRoot().toFile())
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()

    process.outputStream.bufferedWriter().use { writer ->
      writer.write(JsonSupport.mapToJsonString(arguments))
      writer.newLine()
    }
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    require(exitCode == 0) {
      stderr.ifBlank { "Python MCP bridge exited with $exitCode." }
    }
    val decoded = JsonSupport.parseObjectOrNull(stdout.trim())
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
    return requireNotNull(decoded) {
      "Python MCP bridge returned non-object JSON for '$toolName'."
    }
  }

  private fun pythonCommand(): List<String> {
    val arch = System.getenv("SKILL_BILL_PYTHON_ARCH").orEmpty()
    val executable = pythonExecutable()
    return if (arch.isNotBlank() && Path.of("/usr/bin/arch").exists()) {
      listOf("/usr/bin/arch", "-$arch", executable)
    } else {
      listOf(executable)
    }
  }

  private fun pythonExecutable(): String {
    val explicit = System.getenv("SKILL_BILL_PYTHON").orEmpty()
    if (explicit.isNotBlank()) {
      return explicit
    }
    val repoPython = repoRoot().resolve(".venv").resolve("bin").resolve("python3")
    return if (repoPython.exists()) repoPython.toString() else "python3"
  }

  private fun repoRoot(): Path {
    val current = Path.of("").toAbsolutePath().normalize()
    return when {
      current.resolve("skill_bill").exists() -> current
      current.parent?.resolve("skill_bill")?.exists() == true -> current.parent
      else -> current
    }
  }
}

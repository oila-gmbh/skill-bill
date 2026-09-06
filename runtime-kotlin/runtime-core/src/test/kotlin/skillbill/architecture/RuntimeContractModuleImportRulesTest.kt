package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeContractModuleImportRulesTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) workingDir.parent else workingDir
    }

  @Test
  fun `runtime-domain imports no serialization or filesystem library`() {
    assertEquals(
      emptyList(),
      forbiddenImportViolations(
        "runtime-domain",
        listOf("java.io.", "java.nio.charset.", "com.fasterxml.", "kotlinx.serialization.", "org.yaml."),
      ),
      "runtime-domain must reach a wire format only through the types its callers hand it.",
    )
  }

  @Test
  fun `runtime-ports imports no adapter machinery`() {
    assertEquals(
      emptyList(),
      forbiddenImportViolations(
        "runtime-ports",
        listOf("java.io.", "java.nio.file.Files", "kotlinx.serialization.", "me.tatarka.inject.", "org.yaml."),
      ),
      "runtime-ports declares interfaces and DTOs; an adapter import means behaviour landed in the port.",
    )
  }

  private fun forbiddenImportViolations(module: String, forbiddenPrefixes: List<String>): List<String> {
    val root = runtimeRoot.resolve("$module/src/main")
    if (!Files.isDirectory(root)) return emptyList()
    return Files.walk(root).use { paths ->
      paths.filter { Files.isRegularFile(it) && it.extension == "kt" }.toList()
    }.flatMap { path ->
      Files.readString(path).lineSequence()
        .filter { line -> line.startsWith("import ") }
        .filter { line -> forbiddenPrefixes.any { prefix -> line.removePrefix("import ").startsWith(prefix) } }
        .map { line -> "${path.fileName}: ${line.trim()}" }
        .toList()
    }.sorted()
  }
}

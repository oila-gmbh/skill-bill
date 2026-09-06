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
      forbiddenImportViolations("runtime-domain", DOMAIN_FORBIDDEN_IMPORT_PREFIXES),
      "runtime-domain must reach a wire format or a filesystem only through the types its callers hand it.",
    )
  }

  @Test
  fun `runtime-ports imports no adapter machinery`() {
    assertEquals(
      emptyList(),
      forbiddenImportViolations("runtime-ports", PORTS_FORBIDDEN_IMPORT_PREFIXES),
      "runtime-ports declares interfaces and DTOs; an adapter import means behaviour landed in the port.",
    )
  }

  @Test
  fun `scanner rejects the imports each module bans and accepts the neighbours it allows`() {
    val source =
      """
      package skillbill.model

      import java.nio.file.Files
      import java.nio.file.Path
      import java.time.Instant
      import kotlin.text.Regex
      """.trimIndent()

    assertEquals(
      listOf("Domain.kt: import java.nio.file.Files", "Domain.kt: import java.nio.file.Path"),
      forbiddenImportsIn("Domain.kt", source, DOMAIN_FORBIDDEN_IMPORT_PREFIXES),
      "runtime-domain bans every java.nio import, not just the charset corner of it.",
    )
    assertEquals(
      listOf("Ports.kt: import java.nio.file.Files"),
      forbiddenImportsIn("Ports.kt", source, PORTS_FORBIDDEN_IMPORT_PREFIXES),
      "runtime-ports may still name a Path in a DTO; only filesystem access is banned there.",
    )
  }

  private fun forbiddenImportViolations(module: String, forbiddenPrefixes: List<String>): List<String> {
    val root = runtimeRoot.resolve("$module/src/main")
    if (!Files.isDirectory(root)) return emptyList()
    return Files.walk(root).use { paths ->
      paths.filter { Files.isRegularFile(it) && it.extension == "kt" }.toList()
    }.flatMap { path ->
      forbiddenImportsIn(path.fileName.toString(), Files.readString(path), forbiddenPrefixes)
    }.sorted()
  }

  private fun forbiddenImportsIn(fileName: String, source: String, forbiddenPrefixes: List<String>): List<String> =
    source.lineSequence()
      .filter { line -> line.startsWith("import ") }
      .filter { line -> forbiddenPrefixes.any { prefix -> line.removePrefix("import ").startsWith(prefix) } }
      .map { line -> "$fileName: ${line.trim()}" }
      .toList()

  private companion object {
    val DOMAIN_FORBIDDEN_IMPORT_PREFIXES = listOf(
      "com.fasterxml.",
      "java.io.",
      "java.nio.",
      "kotlinx.serialization.",
      "org.yaml.",
    )
    val PORTS_FORBIDDEN_IMPORT_PREFIXES = listOf(
      "java.io.",
      "java.nio.file.Files",
      "kotlinx.serialization.",
      "me.tatarka.inject.",
      "org.yaml.",
    )
  }
}

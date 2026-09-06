package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.test.Test
import kotlin.test.assertEquals

object PortNullObjectCensus {
  private val declaration = Regex("""(?<!data )\bobject\s+((?:Unavailable|Noop|Empty|Unconfigured)\w*)""")

  fun namesIn(source: String): Set<String> = declaration.findAll(source).map { it.groupValues[1] }.toSet()
}

class PortNullObjectAbsenceArchitectureTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) workingDir.parent else workingDir
    }

  @Test
  fun `no runtime module declares a null-object substitute in main source`() {
    val declarations = RuntimeModuleCatalog.declaredGradleModules
      .map { runtimeRoot.resolve("$it/src/main") }
      .filter { Files.isDirectory(it) }
      .flatMap { root ->
        Files.walk(root).use { paths ->
          paths
            .filter { Files.isRegularFile(it) && it.extension == "kt" }
            .toList()
        }
      }
      .flatMap { path -> PortNullObjectCensus.namesIn(Files.readString(path)).map { "$it (${path.fileName})" } }
      .sorted()

    assertEquals(
      emptyList(),
      declarations,
      "A Noop, Unavailable, Empty, or Unconfigured substitute reached production. Make the port " +
        "nullable at the reached call site and move the substitute into that module's testFixtures.",
    )
  }

  @Test
  fun `the census flags a substitute declaration and ignores a sealed data object case`() {
    assertEquals(
      setOf("NoopWorkflowGitOperations"),
      PortNullObjectCensus.namesIn("object NoopWorkflowGitOperations : WorkflowGitOperations"),
    )
    assertEquals(
      emptySet(),
      PortNullObjectCensus.namesIn("  data object Empty : ValidationGateTriageResult"),
    )
  }
}

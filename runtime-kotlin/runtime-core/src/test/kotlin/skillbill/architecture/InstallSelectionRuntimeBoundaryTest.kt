package skillbill.architecture

import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.error.MissingInstallSelectionRecordError
import skillbill.model.RuntimeContext
import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallSelectionRuntimeBoundaryTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) {
        workingDir.parent
      } else {
        workingDir
      }
    }

  @Test
  fun `runtime component exposes shared install selection persistence port`() {
    val home = Files.createTempDirectory("skillbill-install-selection-di")
    val component = RuntimeComponent::class.create(RuntimeContext(environment = emptyMap(), userHome = home))

    assertFailsWith<MissingInstallSelectionRecordError> {
      component.installSelectionPersistencePort.readLatestSuccessfulSelection(
        ReadLatestSuccessfulInstallSelectionRequest(home),
      )
    }
  }

  @Test
  fun `shared install selection runtime layers do not depend on runtime desktop`() {
    listOf("runtime-domain", "runtime-ports", "runtime-infra-fs", "runtime-core").forEach { moduleName ->
      val buildFile = Files.readString(runtimeRoot.resolve("$moduleName/build.gradle.kts"))
      assertFalse(
        buildFile.contains("project(\":runtime-desktop"),
        "$moduleName must not depend on runtime-desktop modules",
      )
      assertTrue(
        sourceFiles(moduleName).flatMap(::imports).none { importedName ->
          importedName.startsWith("skillbill.desktop")
        },
        "$moduleName must not import desktop-only packages",
      )
    }
  }

  private fun sourceFiles(moduleName: String): List<String> {
    val sourceRoot = runtimeRoot.resolve("$moduleName/src/main/kotlin")
    return Files.walk(sourceRoot).use { stream ->
      stream
        .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
        .map(Files::readString)
        .toList()
    }
  }

  private fun imports(source: String): List<String> =
    importPattern.findAll(source).map { match -> match.groupValues[1].substringBefore(" as ") }.toList()

  private companion object {
    val importPattern: Regex = Regex("^import\\s+([A-Za-z0-9_.*]+)", RegexOption.MULTILINE)
  }
}

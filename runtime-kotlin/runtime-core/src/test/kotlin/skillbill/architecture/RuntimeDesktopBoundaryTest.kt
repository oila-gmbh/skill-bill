package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeDesktopBoundaryTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) {
        workingDir.parent
      } else {
        workingDir
      }
    }

  private val sharedModuleNames =
    listOf(
      "runtime-application",
      "runtime-contracts",
      "runtime-core",
      "runtime-domain",
      "runtime-infra-fs",
      "runtime-infra-http",
      "runtime-infra-sqlite",
      "runtime-ports",
    )

  @Test
  fun `shared runtime layers stay independent of desktop ui frameworks`() {
    val violations =
      sharedModuleNames.flatMap { moduleName ->
        sourceFiles(moduleName).flatMap { file ->
          file.imports
            .filter { importedName ->
              desktopUiImports.any(importedName::startsWith)
            }
            .map { importedName -> "${file.relativePath} imports $importedName" }
        }
      }

    assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
  }

  @Test
  fun `shared runtime modules do not apply compose plugins or dependencies`() {
    sharedModuleNames.forEach { moduleName ->
      val buildFile = Files.readString(runtimeRoot.resolve("$moduleName/build.gradle.kts"))
      assertFalse(
        buildFile.contains("compose"),
        "$moduleName must not apply Compose plugins or depend on Compose artifacts",
      )
    }
  }

  private fun sourceFiles(moduleName: String): List<SourceFile> {
    val sourceRoot = runtimeRoot.resolve("$moduleName/src/main/kotlin")
    return Files.walk(sourceRoot).use { stream ->
      stream
        .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
        .map(::sourceFile)
        .toList()
    }
  }

  private fun sourceFile(path: Path): SourceFile {
    val source = Files.readString(path)
    return SourceFile(
      relativePath = runtimeRoot.relativize(path).toString().replace('\\', '/'),
      imports = importPattern.findAll(source).map { it.groupValues[1].substringBefore(" as ") }.toList(),
    )
  }

  private data class SourceFile(
    val relativePath: String,
    val imports: List<String>,
  )

  private companion object {
    val desktopUiImports =
      listOf(
        "androidx.compose",
        "org.jetbrains.compose",
        "skillbill.desktop",
      )
    val importPattern: Regex = Regex("^import\\s+([A-Za-z0-9_.*]+)", RegexOption.MULTILINE)
  }
}

package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeDesktopBoundaryTest {
  @Test
  fun `desktop production sources do not special case the shipped agent addon slug`() {
    val desktopSources = Files.walk(runtimeRoot.resolve("runtime-desktop")).use { paths ->
      paths
        .filter(Files::isRegularFile)
        .filter { path -> path.toString().contains("/src/") && !path.toString().contains("Test/") }
        .filter { path -> path.fileName.toString().endsWith(".kt") }
        .toList()
    }

    val specialCases = desktopSources.filter { path -> Files.readString(path).contains("execution-budget") }

    assertTrue(specialCases.isEmpty(), "desktop production must discover agent add-ons dynamically: $specialCases")
  }

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

  @Test
  fun `desktop commonMain production sources do not import jvm filesystem APIs`() {
    val violations =
      desktopCommonMainFiles().flatMap { file ->
        file.imports
          .filter { importedName -> jvmFilesystemImports.any(importedName::startsWith) }
          .map { importedName -> "${file.relativePath} imports $importedName" }
      }

    assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
  }

  @Test
  fun `desktop commonMain production sources use injected dispatchers`() {
    val violations =
      desktopCommonMainFiles().flatMap { file ->
        val source = stripCommentsAndStringLiterals(Files.readString(file.path))
        dispatcherLiteralPattern.findAll(source).map { match ->
          "${file.relativePath} contains ${match.value}"
        }.toList()
      }

    assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
  }

  @Test
  fun `desktop jvmMain production sources do not block UI init with runBlocking`() {
    val violations =
      desktopJvmMainFiles().flatMap { path ->
        val source = stripCommentsAndStringLiterals(Files.readString(path))
        runBlockingPattern.findAll(source).map { match ->
          "${runtimeRoot.relativize(path).toString().replace('\\', '/')} contains ${match.value}"
        }.toList()
      }

    assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
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
      path = path,
      relativePath = runtimeRoot.relativize(path).toString().replace('\\', '/'),
      imports = importPattern.findAll(source).map { it.groupValues[1].substringBefore(" as ") }.toList(),
    )
  }

  private fun desktopCommonMainFiles(): List<SourceFile> =
    kotlinFiles(runtimeRoot.resolve("runtime-desktop")).filter { path ->
      path.toString().replace('\\', '/').contains("/src/commonMain/kotlin/")
    }.map(::sourceFile)

  private fun desktopJvmMainFiles(): List<Path> = kotlinFiles(runtimeRoot.resolve("runtime-desktop")).filter { path ->
    path.toString().replace('\\', '/').contains("/src/jvmMain/kotlin/")
  }

  private fun kotlinFiles(root: Path): List<Path> = Files.walk(root).use { stream ->
    stream
      .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
      .toList()
  }

  private fun stripCommentsAndStringLiterals(source: String): String {
    var stripped = blockCommentPattern.replace(source, " ")
    stripped = lineCommentPattern.replace(stripped, " ")
    stripped = tripleQuotedStringPattern.replace(stripped, " ")
    stripped = singleLineStringPattern.replace(stripped, " ")
    return stripped
  }

  private data class SourceFile(
    val path: Path,
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
    val jvmFilesystemImports =
      listOf(
        "java.nio",
        "java.io",
      )
    val importPattern: Regex = Regex("^import\\s+([A-Za-z0-9_.*]+)", RegexOption.MULTILINE)
    val dispatcherLiteralPattern: Regex = Regex("\\bDispatchers\\.")
    val runBlockingPattern: Regex = Regex("\\brunBlocking\\b")
    val blockCommentPattern: Regex = Regex("/\\*[\\s\\S]*?\\*/")
    val lineCommentPattern: Regex = Regex("//[^\\n]*")
    val tripleQuotedStringPattern: Regex = Regex("\"\"\"[\\s\\S]*?\"\"\"")
    val singleLineStringPattern: Regex = Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"")
  }
}

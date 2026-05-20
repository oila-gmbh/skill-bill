package skillbill.desktop.feature.skillbill.ui

import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopColorTokenBoundaryTest {

  @Test
  fun `desktop sources outside design-system do not author raw Compose colors`() {
    val repoRoot = repoRootFromTest()
    val files = desktopKotlinFilesOutsideDesignSystem(repoRoot)
    val violations = files.flatMap { path -> violationsIn(repoRoot, path) }

    assertTrue(
      violations.isEmpty(),
      "Raw Compose color usage must stay inside runtime-desktop/core/designsystem:\n" +
        violations.joinToString(separator = "\n"),
    )
  }

  @Test
  fun `guardrail scans jvm source sets outside design-system`() {
    val repoRoot = repoRootFromTest()
    val relativePaths = desktopKotlinFilesOutsideDesignSystem(repoRoot)
      .map { path -> repoRoot.relativize(path).invariantSeparatorsPathString }

    assertTrue(
      relativePaths.any { path -> path.contains("/src/jvmMain/") },
      "Expected raw color guardrail to scan runtime-desktop jvmMain Kotlin sources.",
    )
    assertFalse(
      relativePaths.any { path -> path.contains("/runtime-desktop/core/designsystem/") },
      "Design-system files are allowed to own raw color tokens and must stay outside this guardrail.",
    )
  }

  private fun desktopKotlinFilesOutsideDesignSystem(repoRoot: Path): List<Path> {
    val runtimeDesktop = repoRoot.resolve("runtime-kotlin/runtime-desktop")
    Files.walk(runtimeDesktop).use { paths ->
      return paths.asSequence()
        .filter(Files::isRegularFile)
        .filter { path -> path.fileName.toString().endsWith(".kt") }
        .filter { path -> path.invariantSeparatorsPathString.contains("/src/") }
        .filterNot { path -> path.invariantSeparatorsPathString.contains("/build/") }
        .filterNot { path -> path.invariantSeparatorsPathString.contains("/generated/") }
        .filterNot { path -> path.invariantSeparatorsPathString.contains("/runtime-desktop/core/designsystem/") }
        .sortedBy { path -> repoRoot.relativize(path).invariantSeparatorsPathString }
        .toList()
    }
  }

  private fun violationsIn(repoRoot: Path, path: Path): List<String> {
    val relativePath = repoRoot.relativize(path).invariantSeparatorsPathString
    return Files.readAllLines(path).flatMapIndexed { index, line ->
      bannedPatterns.mapNotNull { pattern ->
        if (pattern.regex.containsMatchIn(line)) {
          "$relativePath:${index + 1}: ${pattern.description}"
        } else {
          null
        }
      }
    }
  }

  private data class BannedPattern(
    val description: String,
    val regex: Regex,
  )

  private companion object {
    val bannedPatterns = listOf(
      BannedPattern(
        description = "raw hexadecimal Compose color literal",
        regex = Regex("""\bColor\s*\(\s*0x[0-9A-Fa-f]+"""),
      ),
      BannedPattern(
        description = "raw Compose singleton color",
        regex = Regex("""\bColor\.(?:Black|White|Transparent)\b"""),
      ),
      BannedPattern(
        description = "direct Compose Color import",
        regex = Regex("""^\s*import\s+androidx\.compose\.ui\.graphics\.Color\s*$"""),
      ),
    )
  }
}

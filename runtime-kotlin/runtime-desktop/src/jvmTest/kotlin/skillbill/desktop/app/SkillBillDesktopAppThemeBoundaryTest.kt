package skillbill.desktop.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillBillDesktopAppThemeBoundaryTest {

  @Test
  fun `desktop app keeps the root theme window route boundary`() {
    val sourcePath = sourceFile(
      "src/commonMain/kotlin/skillbill/desktop/app/SkillBillDesktopApp.kt",
      "runtime-kotlin/runtime-desktop/src/commonMain/kotlin/skillbill/desktop/app/SkillBillDesktopApp.kt",
    )
    val source = Files.readString(sourcePath)

    val themeIndex = source.indexOf("SkillBillAppTheme {")
    val themeEndIndex = source.findMatchingBrace(themeIndex)
    val windowIndex = source.indexOf("SkillBillWindow {")
    val windowEndIndex = source.findMatchingBrace(windowIndex)
    val routeIndexes = Regex("""\bSkillBillRoute\(""").findAll(source).map { it.range.first }.toList()

    // This module's app boundary depends on the real desktop user component graph, so this
    // regression check intentionally stays at the source seam: it verifies the single root
    // SkillBillAppTheme provider still encloses the window and both feature routes without
    // constructing the production DI graph in a JVM UI test.
    assertTrue(themeIndex >= 0, "SkillBillAppTheme must wrap the app content.")
    assertTrue(windowIndex in themeIndex..themeEndIndex, "SkillBillWindow must be inside SkillBillAppTheme.")
    assertTrue(routeIndexes.isNotEmpty(), "SkillBillRoute must be present inside the desktop app.")
    routeIndexes.forEach { routeIndex ->
      assertTrue(routeIndex in windowIndex..windowEndIndex, "Every SkillBillRoute must stay inside SkillBillWindow.")
      assertTrue(routeIndex in themeIndex..themeEndIndex, "Every SkillBillRoute must stay inside SkillBillAppTheme.")
    }
    assertEquals(1, Regex("""SkillBillAppTheme\s*\{""").findAll(source).count())
    assertTrue(source.contains("import skillbill.desktop.core.designsystem.SkillBillAppTheme"))
    assertEquals(0, Regex("""\b(MaterialTheme|SkillBillMaterialTheme)\s*\{""").findAll(source).count())
  }

  private fun String.findMatchingBrace(callIndex: Int): Int {
    require(callIndex >= 0) { "Call index must be valid." }
    val openBraceIndex = indexOf('{', startIndex = callIndex)
    require(openBraceIndex >= 0) { "Expected call at $callIndex to open a block." }

    var depth = 0
    for (index in openBraceIndex until length) {
      when (this[index]) {
        '{' -> depth += 1
        '}' -> {
          depth -= 1
          if (depth == 0) return index
        }
      }
    }
    error("No matching closing brace for block at $callIndex.")
  }

  private fun sourceFile(vararg relativePaths: String): Path {
    var current = Path.of("").toAbsolutePath()
    while (true) {
      relativePaths.forEach { relativePath ->
        val candidate = current.resolve(relativePath)
        if (Files.exists(candidate)) return candidate
      }
      current = current.parent ?: error(
        "Could not locate ${relativePaths.toList()} from ${Path.of("").toAbsolutePath()}",
      )
    }
  }
}

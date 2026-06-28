package skillbill.desktop.feature.skillbill.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import skillbill.desktop.core.designsystem.SkillBillColor
import skillbill.desktop.core.designsystem.SkillBillDarkThemeTokens
import skillbill.desktop.core.designsystem.SkillBillLightThemeTokens
import skillbill.desktop.core.designsystem.SkillBillMaterialTheme
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SkillBillFrameTokenWiringTest {

  @Test
  fun `code pane colors pair light tokens with light pane background`() = runComposeUiTest {
    var colors: CodePaneColors? = null

    setContent {
      SkillBillMaterialTheme(darkTheme = false) {
        colors = codePaneColors()
      }
    }

    waitForIdle()

    val paneColors = colors ?: error("Code pane colors were not captured.")
    assertEquals(SkillBillLightThemeTokens.syntax.yaml, paneColors.yaml)
  }

  @Test
  fun `code pane colors pair dark tokens with dark pane background`() = runComposeUiTest {
    var colors: CodePaneColors? = null

    setContent {
      SkillBillMaterialTheme(darkTheme = true) {
        colors = codePaneColors()
      }
    }

    waitForIdle()

    val paneColors = colors ?: error("Code pane colors were not captured.")
    assertEquals(SkillBillDarkThemeTokens.syntax.yaml, paneColors.yaml)
  }

  @Test
  fun `primary controls read frame onPrimary token`() = runComposeUiTest {
    var lightForeground: SkillBillColor? = null
    var darkForeground: SkillBillColor? = null

    setContent {
      SkillBillMaterialTheme(darkTheme = false) {
        lightForeground = workspacePrimaryControlForeground()
      }
      SkillBillMaterialTheme(darkTheme = true) {
        darkForeground = workspacePrimaryControlForeground()
      }
    }

    waitForIdle()

    assertEquals(SkillBillLightThemeTokens.frame.onPrimary, lightForeground)
    assertEquals(SkillBillDarkThemeTokens.frame.onPrimary, darkForeground)
    assertNotEquals(lightForeground, darkForeground)
  }

  @Test
  fun `diff parsing stays out of core design-system tokens`() {
    val sourcePath = repoRootFromTest()
      .resolve("runtime-kotlin/runtime-desktop/core/designsystem/src/commonMain/kotlin")
      .resolve("skillbill/desktop/core/designsystem/SkillBillTokens.kt")
    val source = Files.readString(sourcePath)

    assertFalse(source.contains("colorForLine"))
    assertFalse(source.contains("startsWith(\"@@\")"))
  }

  @Test
  fun `frame UI does not import raw design-system frame colors`() {
    val sourcePath = repoRootFromTest()
      .resolve("runtime-kotlin/runtime-desktop/feature/skillbill/src/commonMain/kotlin")
      .resolve("skillbill/desktop/feature/skillbill/ui/SkillBillFrame.kt")
    val source = Files.readString(sourcePath)
    val rawFrameColorImport = Regex(
      pattern = """
        import\s+skillbill\.desktop\.core\.designsystem\.
        (SkillBillAmber|SkillBillBlack|SkillBillFrameColor|SkillBillGreen|SkillBillLine|
        SkillBillMuted|SkillBillOnYellow|SkillBillPanel|SkillBillPanelRaised|SkillBillRed|
        SkillBillSteel|SkillBillSteelDark|SkillBillText|SkillBillTransparent|SkillBillYellow)\b
      """.trimIndent(),
      options = setOf(RegexOption.COMMENTS),
    )

    assertFalse(
      rawFrameColorImport.containsMatchIn(source),
      "SkillBillFrame.kt must use SkillBillTheme.frameTokens instead of raw design-system color imports.",
    )
  }

  @Test
  fun `feature and core UI composables read dimensions from tokens with no inline dp literals`() {
    val repo = repoRootFromTest()
    val uiRoots = listOf(
      repo.resolve(
        "runtime-kotlin/runtime-desktop/feature/skillbill/src/commonMain/kotlin/skillbill/desktop/feature/skillbill/ui",
      ),
      repo.resolve("runtime-kotlin/runtime-desktop/core/ui/src/commonMain/kotlin/skillbill/desktop/core/ui"),
    )
    val dpLiteral = Regex("""\d+(\.\d+)?\.dp""")
    val offenders = mutableListOf<String>()

    uiRoots.filter { Files.isDirectory(it) }.forEach { root ->
      Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
          .forEach { file -> collectDpLiterals(file, dpLiteral, offenders) }
      }
    }

    assertTrue(
      offenders.isEmpty(),
      "UI composables must read dimensions from SkillBillDimens/SkillBillMetrics; " +
        "found inline dp literals:\n" + offenders.joinToString("\n"),
    )
  }

  private fun collectDpLiterals(file: Path, dpLiteral: Regex, offenders: MutableList<String>) {
    Files.readAllLines(file).forEachIndexed { index, line ->
      if (line.contains("import ")) return@forEachIndexed
      dpLiteral.find(line)?.let { offenders += "${file.fileName}:$index: ${line.trim()}" }
    }
  }
}

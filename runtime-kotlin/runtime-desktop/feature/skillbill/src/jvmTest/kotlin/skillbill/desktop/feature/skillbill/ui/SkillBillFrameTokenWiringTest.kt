package skillbill.desktop.feature.skillbill.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import skillbill.desktop.core.designsystem.SkillBillColor
import skillbill.desktop.core.designsystem.SkillBillDarkThemeTokens
import skillbill.desktop.core.designsystem.SkillBillLightThemeTokens
import skillbill.desktop.core.designsystem.SkillBillMaterialTheme
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
class SkillBillFrameTokenWiringTest {

  @Test
  fun `diff line classification maps unified diff prefixes to feature UI tokens`() {
    assertEquals(DiffLineRole.Metadata, diffRoleForLine("+++ b/file.kt"))
    assertEquals(DiffLineRole.Metadata, diffRoleForLine("--- a/file.kt"))
    assertEquals(DiffLineRole.Hunk, diffRoleForLine("@@ -1 +1 @@"))
    assertEquals(DiffLineRole.Addition, diffRoleForLine("+added"))
    assertEquals(DiffLineRole.Deletion, diffRoleForLine("-removed"))
    assertEquals(DiffLineRole.Context, diffRoleForLine(" unchanged"))
  }

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
    assertEquals(SkillBillLightThemeTokens.diff, paneColors.diff)
    assertEquals(SkillBillLightThemeTokens.syntax.yaml, paneColors.yaml)
    assertEquals(SkillBillLightThemeTokens.syntax.yaml.scalar, paneColors.yamlFallback)
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
    assertEquals(SkillBillDarkThemeTokens.diff, paneColors.diff)
    assertEquals(SkillBillDarkThemeTokens.syntax.yaml, paneColors.yaml)
    assertEquals(SkillBillDarkThemeTokens.syntax.yaml.scalar, paneColors.yamlFallback)
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
}

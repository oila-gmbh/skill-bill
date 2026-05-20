package skillbill.desktop.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SkillBillThemeTokensTest {

  @Test
  fun `light and dark material schemes are distinct and readable`() {
    assertNotEquals(SkillBillLightColorScheme.background, SkillBillDarkColorScheme.background)
    assertNotEquals(SkillBillLightColorScheme.surface, SkillBillDarkColorScheme.surface)
    assertNotEquals(SkillBillLightColorScheme.onSurface, SkillBillDarkColorScheme.onSurface)

    assertReadable(SkillBillLightColorScheme.onBackground, SkillBillLightColorScheme.background)
    assertReadable(SkillBillLightColorScheme.onSurface, SkillBillLightColorScheme.surface)
    assertReadable(SkillBillLightColorScheme.primary, SkillBillLightColorScheme.surface)
    assertReadable(SkillBillLightColorScheme.onPrimary, SkillBillLightColorScheme.primary)
    assertReadable(SkillBillOnYellow, SkillBillYellow)
    assertReadable(SkillBillDarkColorScheme.onBackground, SkillBillDarkColorScheme.background)
    assertReadable(SkillBillDarkColorScheme.onSurface, SkillBillDarkColorScheme.surface)
    assertReadable(SkillBillDarkColorScheme.onPrimary, SkillBillDarkColorScheme.primary)
  }

  @Test
  fun `theme tokens expose required text field semantic syntax diff and frame colors`() {
    val tokens = SkillBillDarkThemeTokens

    assertEquals(SkillBillText, tokens.textField.text)
    assertEquals(SkillBillMuted.copy(alpha = 0.86f), tokens.textField.placeholder)
    assertEquals(SkillBillLine, tokens.textField.border)
    assertEquals(SkillBillYellow, tokens.textField.focusedBorder)
    assertEquals(SkillBillLine.copy(alpha = 0.55f), tokens.textField.disabledBorder)
    assertEquals(SkillBillYellow, tokens.textField.cursor)

    assertEquals(SkillBillPanelRaised, tokens.semanticTones.dialog.container)
    assertEquals(Color.Black.copy(alpha = 0.72f), tokens.semanticTones.scrim)
    assertReadable(tokens.semanticTones.warningBanner.content, tokens.semanticTones.warningBanner.container)
    assertReadable(tokens.semanticTones.successBanner.content, tokens.semanticTones.successBanner.container)
    assertReadable(tokens.semanticTones.errorBanner.content, tokens.semanticTones.errorBanner.container)

    assertEquals(SkillBillYellow, tokens.syntax.yaml.key)
    assertEquals(SkillBillGreen, tokens.syntax.yaml.string)
    assertEquals(SkillBillAmber, tokens.syntax.yaml.marker)

    assertEquals(SkillBillBlack, tokens.frame.background)
    assertEquals(SkillBillPanel, tokens.frame.panel)
    assertEquals(SkillBillPanelRaised, tokens.frame.raised)
    assertEquals(SkillBillFrameColor, tokens.frame.sidebar)
    assertEquals(SkillBillLine, tokens.frame.line)
    assertEquals(SkillBillText, tokens.frame.text)
    assertEquals(SkillBillMuted, tokens.frame.muted)
    assertEquals(SkillBillSteel, tokens.frame.subtle)
    assertEquals(SkillBillYellow, tokens.frame.primary)
    assertEquals(SkillBillOnYellow, tokens.frame.onPrimary)
    assertEquals(SkillBillGreen, tokens.frame.status.contentColorFor(SkillBillStatusTone.Success))
    assertEquals(SkillBillAmber, tokens.frame.status.contentColorFor(SkillBillStatusTone.Warning))
    assertEquals(SkillBillRed, tokens.frame.status.contentColorFor(SkillBillStatusTone.Error))
  }

  @Test
  fun `light and dark composition locals provide matching token sets`() = runComposeUiTest {
    var lightTokens: SkillBillThemeTokens? = null
    var darkTokens: SkillBillThemeTokens? = null
    var lightColors: androidx.compose.material3.ColorScheme? = null
    var darkColors: androidx.compose.material3.ColorScheme? = null
    var lightGradients: GradientColors? = null
    var darkGradients: GradientColors? = null

    setContent {
      SkillBillMaterialTheme(darkTheme = false) {
        lightTokens = SkillBillTheme.tokens
        lightColors = SkillBillTheme.colors
        lightGradients = SkillBillTheme.gradientColors
      }
      SkillBillMaterialTheme(darkTheme = true) {
        darkTokens = SkillBillTheme.tokens
        darkColors = SkillBillTheme.colors
        darkGradients = SkillBillTheme.gradientColors
      }
    }

    waitForIdle()

    assertEquals(SkillBillLightThemeTokens, lightTokens)
    assertEquals(SkillBillDarkThemeTokens, darkTokens)
    assertEquals(SkillBillLightColorScheme, lightColors)
    assertEquals(SkillBillDarkColorScheme, darkColors)
    assertEquals(SkillBillLightGradientColors, lightGradients)
    assertEquals(GradientColors(), darkGradients)
  }

  @Test
  fun `light and dark token sets provide distinct theme behavior`() {
    assertNotEquals(SkillBillLightThemeTokens.textField.container, SkillBillDarkThemeTokens.textField.container)
    assertNotEquals(SkillBillLightThemeTokens.textField.cursor, SkillBillDarkThemeTokens.textField.cursor)
    assertNotEquals(
      SkillBillLightThemeTokens.semanticTones.dialog.container,
      SkillBillDarkThemeTokens.semanticTones.dialog.container,
    )
    assertNotEquals(SkillBillLightThemeTokens.semanticTones.scrim, SkillBillDarkThemeTokens.semanticTones.scrim)
    assertNotEquals(SkillBillLightThemeTokens.syntax.yaml.key, SkillBillDarkThemeTokens.syntax.yaml.key)
    assertNotEquals(SkillBillLightThemeTokens.diff.context, SkillBillDarkThemeTokens.diff.context)
    assertNotEquals(SkillBillLightThemeTokens.frame.background, SkillBillDarkThemeTokens.frame.background)
    assertNotEquals(SkillBillLightThemeTokens.frame.panel, SkillBillDarkThemeTokens.frame.panel)
    assertNotEquals(SkillBillLightThemeTokens.frame.primary, SkillBillDarkThemeTokens.frame.primary)
    assertNotEquals(SkillBillLightThemeTokens.frame.onPrimary, SkillBillDarkThemeTokens.frame.onPrimary)
  }

  @Test
  fun `light theme tokens keep editor and semantic foregrounds readable on their containers`() {
    val tokens = SkillBillLightThemeTokens
    val background = SkillBillLightColorScheme.background
    val surface = SkillBillLightColorScheme.surface

    assertReadable(tokens.semanticTones.dialog.content, tokens.semanticTones.dialog.container)
    assertReadable(tokens.semanticTones.warningBanner.content, tokens.semanticTones.warningBanner.container)
    assertReadable(tokens.semanticTones.successBanner.content, tokens.semanticTones.successBanner.container)
    assertReadable(tokens.semanticTones.errorBanner.content, tokens.semanticTones.errorBanner.container)

    assertReadable(tokens.textField.text, tokens.textField.container)
    assertReadable(tokens.textField.placeholder, tokens.textField.container)
    assertReadable(tokens.textField.disabledText, tokens.textField.disabledContainer)
    assertReadableNonText(tokens.textField.border, surface)
    assertReadableNonText(tokens.textField.focusedBorder, surface)
    assertReadableNonText(tokens.textField.disabledBorder, surface)
    assertReadableNonText(tokens.textField.cursor, surface)

    assertReadable(tokens.syntax.yaml.comment, background)
    assertReadable(tokens.syntax.yaml.key, background)
    assertReadable(tokens.syntax.yaml.string, background)
    assertReadable(tokens.syntax.yaml.marker, background)
    assertReadable(tokens.syntax.yaml.scalar, background)

    assertReadable(tokens.diff.metadata, background)
    assertReadable(tokens.diff.hunk, background)
    assertReadable(tokens.diff.addition, background)
    assertReadable(tokens.diff.deletion, background)
    assertReadable(tokens.diff.context, background)

    assertReadableFrameTokens(tokens)
  }

  @Test
  fun `dark theme tokens keep frame foregrounds readable on their containers`() {
    assertReadableFrameTokens(SkillBillDarkThemeTokens)
  }

  private fun assertReadable(foreground: Color, background: Color) {
    val ratio = contrastRatio(foreground, background)
    assertTrue(ratio >= MINIMUM_BODY_TEXT_CONTRAST, "Expected contrast >= $MINIMUM_BODY_TEXT_CONTRAST, got $ratio")
  }

  private fun assertReadableNonText(foreground: Color, background: Color) {
    val ratio = contrastRatio(foreground, background)
    assertTrue(ratio >= MINIMUM_NON_TEXT_CONTRAST, "Expected contrast >= $MINIMUM_NON_TEXT_CONTRAST, got $ratio")
  }

  private fun assertReadableFrameTokens(tokens: SkillBillThemeTokens) {
    val frame = tokens.frame

    assertReadable(frame.text, frame.background)
    assertReadable(frame.text, frame.panel)
    assertReadable(frame.text, frame.raised)
    assertReadable(frame.text, frame.sidebar)
    assertReadable(frame.muted, frame.background)
    assertReadable(frame.subtle, frame.background)
    assertReadable(frame.subtle, frame.panel)
    assertReadable(frame.subtle, frame.raised)
    assertReadable(frame.primary, frame.background)
    assertReadable(frame.onPrimary, frame.primary)
    assertReadable(frame.status.success, frame.background)
    assertReadable(frame.status.warning, frame.background)
    assertReadable(frame.status.error, frame.background)
  }

  private fun contrastRatio(foreground: Color, background: Color): Double {
    val effectiveForeground = foreground.compositeOver(background)
    val lighter = maxOf(effectiveForeground.relativeLuminance(), background.relativeLuminance())
    val darker = minOf(effectiveForeground.relativeLuminance(), background.relativeLuminance())
    return (lighter + 0.05) / (darker + 0.05)
  }

  private fun Color.compositeOver(background: Color): Color {
    if (alpha >= 1f) return this
    val inverseAlpha = 1f - alpha
    return Color(
      red = red * alpha + background.red * inverseAlpha,
      green = green * alpha + background.green * inverseAlpha,
      blue = blue * alpha + background.blue * inverseAlpha,
      alpha = 1f,
    )
  }

  private fun Color.relativeLuminance(): Double {
    fun channel(value: Float): Double {
      val normalized = value.toDouble()
      return if (normalized <= 0.03928) {
        normalized / 12.92
      } else {
        ((normalized + 0.055) / 1.055).pow(2.4)
      }
    }
    return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
  }

  private companion object {
    const val MINIMUM_BODY_TEXT_CONTRAST = 4.5
    const val MINIMUM_NON_TEXT_CONTRAST = 3.0
  }
}

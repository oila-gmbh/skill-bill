package skillbill.desktop.core.designsystem

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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
  fun `SkillBillDimens pins border spacing padding and component sizing primitives`() {
    assertEquals(0.dp, SkillBillDimens.borderNone)
    assertEquals(1.dp, SkillBillDimens.hairline)
    assertEquals(1.4.dp, SkillBillDimens.divider)

    assertEquals(2.dp, SkillBillDimens.spacingXs)
    assertEquals(4.dp, SkillBillDimens.spacingSm)
    assertEquals(6.dp, SkillBillDimens.spacingMd)
    assertEquals(8.dp, SkillBillDimens.spacingLg)
    assertEquals(10.dp, SkillBillDimens.spacingXl)
    assertEquals(12.dp, SkillBillDimens.spacing2xl)
    assertEquals(14.dp, SkillBillDimens.spacing3xl)
    assertEquals(16.dp, SkillBillDimens.spacing4xl)
    assertEquals(18.dp, SkillBillDimens.spacing5xl)

    assertEquals(3.dp, SkillBillDimens.space3)
    assertEquals(5.dp, SkillBillDimens.space5)
    assertEquals(7.dp, SkillBillDimens.space7)
    assertEquals(9.dp, SkillBillDimens.space9)

    assertEquals(2.dp, SkillBillDimens.padXs)
    assertEquals(4.dp, SkillBillDimens.padSm)
    assertEquals(6.dp, SkillBillDimens.padMd)
    assertEquals(8.dp, SkillBillDimens.padLg)
    assertEquals(10.dp, SkillBillDimens.padXl)
    assertEquals(12.dp, SkillBillDimens.pad2xl)
    assertEquals(14.dp, SkillBillDimens.pad3xl)
    assertEquals(16.dp, SkillBillDimens.pad4xl)
    assertEquals(18.dp, SkillBillDimens.pad5xl)

    assertEquals(26.dp, SkillBillDimens.controlHeightSm)
    assertEquals(28.dp, SkillBillDimens.controlHeightMd)
    assertEquals(30.dp, SkillBillDimens.controlHeightLg)

    assertEquals(30.dp, SkillBillDimens.rowHeightSm)
    assertEquals(38.dp, SkillBillDimens.rowHeightMd)
    assertEquals(44.dp, SkillBillDimens.rowHeightLg)
    assertEquals(52.dp, SkillBillDimens.rowHeightXl)

    assertEquals(14.dp, SkillBillDimens.iconSm)
    assertEquals(15.dp, SkillBillDimens.iconMd)
    assertEquals(18.dp, SkillBillDimens.iconLg)

    assertEquals(18.dp, SkillBillDimens.checkboxSize)
    assertEquals(50.dp, SkillBillDimens.lineNumberWidth)

    assertEquals(560.dp, SkillBillDimens.dialogMinWidth)
    assertEquals(760.dp, SkillBillDimens.dialogMaxWidth)
    assertEquals(640.dp, SkillBillDimens.dialogMaxHeight)
    assertEquals(60.dp, SkillBillDimens.footerButtonMinWidth)

    assertEquals(620.dp, SkillBillDimens.firstRunDialogMinWidth)
    assertEquals(820.dp, SkillBillDimens.firstRunDialogMaxWidth)
    assertEquals(700.dp, SkillBillDimens.firstRunDialogMaxHeight)
  }

  @Test
  fun `SkillBillDimens pins component specific sizing primitives`() {
    assertEquals(20.dp, SkillBillDimens.menuSeparatorHeight)
    assertEquals(27.dp, SkillBillDimens.navTreeRowMinHeight)
    assertEquals(32.dp, SkillBillDimens.listItemHeight)
    assertEquals(35.dp, SkillBillDimens.navPaneHeaderHeight)
    assertEquals(36.dp, SkillBillDimens.editorTabHeight)
    assertEquals(48.dp, SkillBillDimens.commandRowHeight)
    assertEquals(48.dp, SkillBillDimens.scrollThumbMinWidth)

    assertEquals(134.dp, SkillBillDimens.editorTabWidthShort)
    assertEquals(190.dp, SkillBillDimens.editorTabWidthMedium)
    assertEquals(230.dp, SkillBillDimens.editorTabWidthLong)

    assertEquals(140.dp, SkillBillDimens.codeCompletionMaxHeight)
    assertEquals(24.dp, SkillBillDimens.chipMinHeight)
    assertEquals(32.dp, SkillBillDimens.chipMinWidth)
    assertEquals(220.dp, SkillBillDimens.menuMinWidth)

    assertEquals(520.dp, SkillBillDimens.commandPaletteMinWidth)
    assertEquals(720.dp, SkillBillDimens.commandPaletteMaxWidth)
    assertEquals(480.dp, SkillBillDimens.commandPaletteMaxHeight)
    assertEquals(410.dp, SkillBillDimens.commandPaletteListHeight)
    assertEquals(54.dp, SkillBillDimens.commandPaletteTopOffset)
  }

  @Test
  fun `SkillBillMetrics pins structural frame sizes and keeps its original six fields`() {
    assertEquals(40.dp, SkillBillMetrics.toolbarHeight)
    assertEquals(260.dp, SkillBillMetrics.treePaneWidth)
    assertEquals(340.dp, SkillBillMetrics.inspectorPaneWidth)
    assertEquals(260.dp, SkillBillMetrics.bottomDockHeight)
    assertEquals(28.dp, SkillBillMetrics.statusPaneHeight)
    assertEquals(18.dp, SkillBillMetrics.treeIndent)

    assertEquals(38.dp, SkillBillMetrics.editorCommandBarHeight)
    assertEquals(52.dp, SkillBillMetrics.footerHeight)
    assertEquals(44.dp, SkillBillMetrics.dialogHeaderHeight)

    assertEquals(220.dp, SkillBillMetrics.navigationPaneMinWidth)
    assertEquals(540.dp, SkillBillMetrics.navigationPaneMaxWidth)
    assertEquals(7.dp, SkillBillMetrics.navigationPaneResizeHandleWidth)

    assertEquals(22.dp, SkillBillMetrics.navTreeBaseIndent)
    assertEquals(16.dp, SkillBillMetrics.navTreeDepthStep)

    assertEquals(288.dp, SkillBillMetrics.toolbarMenuWidth)
  }

  @Test
  fun `shape scheme and component shapes pin their design radii`() {
    assertEquals(RoundedCornerShape(4.dp), SkillBillShapeScheme.extraSmall)
    assertEquals(RoundedCornerShape(6.dp), SkillBillShapeScheme.small)
    assertEquals(RoundedCornerShape(8.dp), SkillBillShapeScheme.medium)
    assertEquals(RoundedCornerShape(12.dp), SkillBillShapeScheme.large)
    assertEquals(RoundedCornerShape(16.dp), SkillBillShapeScheme.extraLarge)

    assertEquals(SkillBillShapeScheme.extraSmall, SkillBillShapes.extraSmall)
    assertEquals(SkillBillShapeScheme.small, SkillBillShapes.small)
    assertEquals(SkillBillShapeScheme.medium, SkillBillShapes.medium)
    assertEquals(SkillBillShapeScheme.large, SkillBillShapes.large)
    assertEquals(SkillBillShapeScheme.extraLarge, SkillBillShapes.extraLarge)

    assertEquals(RoundedCornerShape(2.dp), SkillBillComponentShapes.checkbox)
    assertEquals(RoundedCornerShape(3.dp), SkillBillComponentShapes.chip)
    assertEquals(RoundedCornerShape(4.dp), SkillBillComponentShapes.previewConsole)
    assertEquals(RoundedCornerShape(5.dp), SkillBillComponentShapes.badge)
    assertEquals(RoundedCornerShape(6.dp), SkillBillComponentShapes.control)
    assertEquals(RoundedCornerShape(8.dp), SkillBillComponentShapes.panel)
    assertEquals(CircleShape, SkillBillComponentShapes.pill)
  }

  @Test
  fun `typography slots and extended type styles pin their design sizes and weights`() {
    assertEquals(16.sp, SkillBillTypography.bodyLarge.fontSize)
    assertEquals(FontWeight.Medium, SkillBillTypography.bodyLarge.fontWeight)
    assertEquals(14.sp, SkillBillTypography.titleSmall.fontSize)
    assertEquals(FontWeight.Medium, SkillBillTypography.titleSmall.fontWeight)
    assertEquals(12.sp, SkillBillTypography.bodySmall.fontSize)
    assertEquals(FontWeight.Medium, SkillBillTypography.bodySmall.fontWeight)
    assertEquals(11.sp, SkillBillTypography.labelSmall.fontSize)
    assertEquals(FontWeight.Medium, SkillBillTypography.labelSmall.fontWeight)

    assertEquals(12.5.sp, SkillBillTypeStyles.code.fontSize)
    assertEquals(FontFamily.Monospace, SkillBillTypeStyles.code.fontFamily)
    assertEquals(FontWeight.Medium, SkillBillTypeStyles.code.fontWeight)
    assertEquals(TextUnit.Unspecified, SkillBillTypeStyles.code.lineHeight)

    assertEquals(12.5.sp, SkillBillTypeStyles.body125.fontSize)
    assertNull(SkillBillTypeStyles.body125.fontFamily)
    assertEquals(FontWeight.Medium, SkillBillTypeStyles.body125.fontWeight)

    assertEquals(13.sp, SkillBillTypeStyles.body13.fontSize)
    assertEquals(FontWeight.Medium, SkillBillTypeStyles.body13.fontWeight)

    assertEquals(13.sp, SkillBillTypeStyles.mono13.fontSize)
    assertEquals(FontFamily.Monospace, SkillBillTypeStyles.mono13.fontFamily)
    assertEquals(FontWeight.SemiBold, SkillBillTypeStyles.mono13.fontWeight)

    assertEquals(11.sp, SkillBillTypeStyles.semiBoldLabel.fontSize)
    assertEquals(FontWeight.SemiBold, SkillBillTypeStyles.semiBoldLabel.fontWeight)

    assertEquals(11.sp, SkillBillTypeStyles.monoBadge.fontSize)
    assertEquals(FontFamily.Monospace, SkillBillTypeStyles.monoBadge.fontFamily)
    assertEquals(FontWeight.Bold, SkillBillTypeStyles.monoBadge.fontWeight)

    assertEquals(10.sp, SkillBillTypeStyles.caption.fontSize)
    assertEquals(FontWeight.Medium, SkillBillTypeStyles.caption.fontWeight)

    assertEquals(10.5.sp, SkillBillTypeStyles.codeCaption.fontSize)
    assertEquals(FontWeight.Medium, SkillBillTypeStyles.codeCaption.fontWeight)
    assertEquals(TextUnit.Unspecified, SkillBillTypeStyles.codeCaption.lineHeight)

    assertEquals(8.sp, SkillBillTypeStyles.microLabel.fontSize)
    assertEquals(FontFamily.Monospace, SkillBillTypeStyles.microLabel.fontFamily)
    assertEquals(FontWeight.Bold, SkillBillTypeStyles.microLabel.fontWeight)

    assertEquals(12.sp, SkillBillTypeStyles.bodySmallNormal.fontSize)
    assertEquals(FontWeight.Normal, SkillBillTypeStyles.bodySmallNormal.fontWeight)
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

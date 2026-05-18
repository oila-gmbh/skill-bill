package skillbill.desktop.core.designsystem

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.graphics.Color

val SkillBillYellow = Color(0xFFF4C430)
internal val SkillBillYellowDeep = Color(0xFFC99717)
internal val SkillBillInk = Color(0xFF0B0B0D)

// SKILL-46 follow-up F-609: the colors below were `internal`; widening to module-public so the
// feature module's dialogs can converge on a single design-system source-of-truth rather than
// declaring parallel hex constants per dialog file. Remaining legacy duplicates in
// SkillBillFrameColor.kt (`Workspace*`) and ScaffoldWizardDialog.kt (`ScaffoldDialog*`) are a known
// follow-up; ConfirmDeletionDialog.kt has been migrated.
val SkillBillBlack = Color(0xFF050506)
val SkillBillFrameColor = Color(0xFF0D0D10)
val SkillBillPanel = Color(0xFF121216)
val SkillBillPanelRaised = Color(0xFF15151A)
val SkillBillLine = Color(0xFF2A2A31)
internal val SkillBillLineRaised = Color(0xFF34343B)
val SkillBillText = Color(0xFFF6F3E7)
internal val SkillBillTextWarm = Color(0xFFF7EFD3)
internal val SkillBillTextSoft = Color(0xFFDDD8C7)
val SkillBillMuted = Color(0xFFB7B1A0)
val SkillBillSteel = Color(0xFFCFD4D8)
val SkillBillSteelDark = Color(0xFF6F7882)
val SkillBillGreen = Color(0xFF60D394)
val SkillBillRed = Color(0xFFFF5F57)
val SkillBillAmber = Color(0xFFFFBD2E)
internal val SkillBillMacGreen = Color(0xFF28C840)
internal val SkillBillHeroGold = Color(0xFFE1AF1D)
internal val SkillBillNodeText = Color(0xFFF5E8AE)

object SkillBillBrandColors {
  val Yellow = SkillBillYellow
  val YellowDeep = SkillBillYellowDeep
  val Green = SkillBillGreen
  val Steel = SkillBillSteel
  val SteelDark = SkillBillSteelDark

  val all = listOf(Yellow, YellowDeep, Green, Steel, SteelDark)
}

internal val SkillBillLightColorScheme = lightColorScheme(
  primary = SkillBillYellow,
  onPrimary = SkillBillInk,
  primaryContainer = SkillBillPanelRaised,
  onPrimaryContainer = SkillBillYellow,
  secondary = SkillBillMuted,
  onSecondary = SkillBillInk,
  secondaryContainer = SkillBillLine,
  onSecondaryContainer = SkillBillText,
  tertiary = SkillBillGreen,
  onTertiary = SkillBillInk,
  tertiaryContainer = SkillBillPanel,
  onTertiaryContainer = SkillBillText,
  error = SkillBillRed,
  onError = SkillBillInk,
  background = SkillBillBlack,
  onBackground = SkillBillText,
  surface = SkillBillFrameColor,
  onSurface = SkillBillText,
  surfaceVariant = SkillBillPanel,
  onSurfaceVariant = SkillBillMuted,
  outline = SkillBillLine,
  surfaceTint = SkillBillYellow,
)

internal val SkillBillDarkColorScheme = darkColorScheme(
  primary = SkillBillYellow,
  onPrimary = SkillBillInk,
  primaryContainer = SkillBillPanelRaised,
  onPrimaryContainer = SkillBillYellow,
  secondary = SkillBillMuted,
  onSecondary = SkillBillInk,
  secondaryContainer = SkillBillPanel,
  onSecondaryContainer = SkillBillText,
  tertiary = SkillBillGreen,
  onTertiary = SkillBillInk,
  tertiaryContainer = SkillBillPanel,
  onTertiaryContainer = SkillBillText,
  error = SkillBillRed,
  onError = SkillBillInk,
  background = SkillBillBlack,
  onBackground = SkillBillText,
  surface = SkillBillFrameColor,
  onSurface = SkillBillText,
  surfaceVariant = SkillBillPanel,
  onSurfaceVariant = SkillBillMuted,
  outline = SkillBillMuted,
  surfaceTint = SkillBillYellow,
)

val SkillBillLightGradientColors = GradientColors(
  primary = SkillBillYellow.copy(alpha = 0.16f),
  secondary = SkillBillHeroGold,
  tertiary = SkillBillPanel,
  neutral = SkillBillFrameColor,
)

@Composable
internal fun skillBillOutlinedTextFieldColors(): TextFieldColors {
  val placeholderColor = SkillBillMuted.copy(alpha = SkillBillContentAlpha.lowEmphasis)
  return OutlinedTextFieldDefaults.colors(
    focusedTextColor = SkillBillTheme.colors.onSurface,
    unfocusedTextColor = SkillBillTheme.colors.onSurface,
    focusedContainerColor = SkillBillTheme.colors.surface,
    unfocusedContainerColor = SkillBillTheme.colors.surface,
    focusedPlaceholderColor = placeholderColor,
    unfocusedPlaceholderColor = placeholderColor,
    disabledPlaceholderColor = placeholderColor,
    focusedBorderColor = SkillBillTheme.colors.primary,
    unfocusedBorderColor = SkillBillTheme.extendedColors.outlineNormal,
    cursorColor = SkillBillTheme.colors.primary,
  )
}

@Composable
internal fun skillBillTextFieldColors(): TextFieldColors {
  val placeholderColor = SkillBillMuted.copy(alpha = SkillBillContentAlpha.lowEmphasis)
  return TextFieldDefaults.colors(
    focusedPlaceholderColor = placeholderColor,
    unfocusedPlaceholderColor = placeholderColor,
    disabledPlaceholderColor = placeholderColor,
    focusedIndicatorColor = SkillBillTheme.colors.primary,
    unfocusedIndicatorColor = SkillBillTheme.extendedColors.outlineNormal,
    errorContainerColor = SkillBillTheme.colors.surface,
    errorIndicatorColor = SkillBillTheme.colors.error,
    cursorColor = SkillBillTheme.colors.primary,
    focusedTextColor = SkillBillTheme.colors.onSurface,
    unfocusedTextColor = SkillBillTheme.colors.onSurface,
    focusedContainerColor = SkillBillTheme.colors.surface,
    unfocusedContainerColor = SkillBillTheme.colors.surface,
  )
}

fun defaultSkillBillColors(
  backgroundVariant: Color = SkillBillBlack,
  surfaceVariant: Color = SkillBillPanel,
  onSurface: Color = SkillBillText,
  outlineNormal: Color = SkillBillLine,
  outlineDisabled: Color = SkillBillLine.copy(alpha = 0.55f),
  onSurfaceInverted: Color = SkillBillInk,
  separator: Color = SkillBillLine,
  scrim: Color = SkillBillText.copy(alpha = 0.38f),
  positive: Color = SkillBillGreen,
  warning: Color = SkillBillAmber,
  statusAttention: Color = SkillBillRed,
  info: Color = SkillBillSteel,
): SkillBillColors = createSkillBillColors(
  backgroundVariant = backgroundVariant,
  surfaceVariant = surfaceVariant,
  onSurface = onSurface,
  outlineNormal = outlineNormal,
  outlineDisabled = outlineDisabled,
  onSurfaceInverted = onSurfaceInverted,
  separator = separator,
  scrim = scrim,
  positive = positive,
  warning = warning,
  statusAttention = statusAttention,
  info = info,
  isMediaTheme = false,
)

fun defaultSkillBillMediaColors(
  backgroundVariant: Color = SkillBillBlack,
  surfaceVariant: Color = SkillBillPanel,
  onSurface: Color = SkillBillText,
  outlineNormal: Color = SkillBillMuted,
  outlineDisabled: Color = SkillBillMuted.copy(alpha = 0.6f),
  onSurfaceInverted: Color = SkillBillInk,
  separator: Color = SkillBillLine,
  scrim: Color = Color.Black.copy(alpha = 0.54f),
  positive: Color = SkillBillGreen,
  warning: Color = SkillBillAmber,
  statusAttention: Color = SkillBillRed,
  info: Color = SkillBillSteel,
): SkillBillColors = createSkillBillColors(
  backgroundVariant = backgroundVariant,
  surfaceVariant = surfaceVariant,
  onSurface = onSurface,
  outlineNormal = outlineNormal,
  outlineDisabled = outlineDisabled,
  onSurfaceInverted = onSurfaceInverted,
  separator = separator,
  scrim = scrim,
  positive = positive,
  warning = warning,
  statusAttention = statusAttention,
  info = info,
  isMediaTheme = true,
)

@Suppress("LongParameterList")
private fun createSkillBillColors(
  backgroundVariant: Color,
  surfaceVariant: Color,
  onSurface: Color,
  outlineNormal: Color,
  outlineDisabled: Color,
  onSurfaceInverted: Color,
  separator: Color,
  scrim: Color,
  positive: Color,
  warning: Color,
  statusAttention: Color,
  info: Color,
  isMediaTheme: Boolean,
): SkillBillColors = SkillBillColors(
  backgroundVariant = backgroundVariant,
  surfaceVariant = surfaceVariant,
  onSurface = onSurface,
  outlineNormal = outlineNormal,
  outlineDisabled = outlineDisabled,
  onSurfaceInverted = onSurfaceInverted,
  separator = separator,
  positive = positive,
  warning = warning,
  scrim = scrim,
  statusAttention = statusAttention,
  info = info,
  isMediaTheme = isMediaTheme,
)

@Suppress("LongParameterList")
class SkillBillColors(
  backgroundVariant: Color,
  surfaceVariant: Color,
  onSurface: Color,
  outlineNormal: Color,
  outlineDisabled: Color,
  onSurfaceInverted: Color,
  separator: Color,
  positive: Color,
  warning: Color,
  info: Color,
  scrim: Color,
  statusAttention: Color,
  isMediaTheme: Boolean,
) {
  var backgroundVariant by mutableStateOf(backgroundVariant, structuralEqualityPolicy())
    internal set
  var surfaceVariant by mutableStateOf(surfaceVariant, structuralEqualityPolicy())
    internal set
  var onSurface by mutableStateOf(onSurface, structuralEqualityPolicy())
    internal set
  var outlineNormal by mutableStateOf(outlineNormal, structuralEqualityPolicy())
    internal set
  var outlineDisabled by mutableStateOf(outlineDisabled, structuralEqualityPolicy())
    internal set
  var onSurfaceInverted by mutableStateOf(onSurfaceInverted, structuralEqualityPolicy())
    internal set
  var separator by mutableStateOf(separator, structuralEqualityPolicy())
    internal set
  var positive by mutableStateOf(positive, structuralEqualityPolicy())
    internal set
  var warning by mutableStateOf(warning, structuralEqualityPolicy())
    internal set
  var scrim by mutableStateOf(scrim, structuralEqualityPolicy())
    internal set
  var isMediaTheme by mutableStateOf(isMediaTheme, structuralEqualityPolicy())
    internal set
  var statusAttention by mutableStateOf(statusAttention, structuralEqualityPolicy())
    internal set
  var info by mutableStateOf(info, structuralEqualityPolicy())
    internal set

  fun copy(
    backgroundVariant: Color = this.backgroundVariant,
    surfaceVariant: Color = this.surfaceVariant,
    onSurface: Color = this.onSurface,
    outlineNormal: Color = this.outlineNormal,
    outlineDisabled: Color = this.outlineDisabled,
    onSurfaceInverted: Color = this.onSurfaceInverted,
    separator: Color = this.separator,
    positive: Color = this.positive,
    warning: Color = this.warning,
    scrim: Color = this.scrim,
    statusAttention: Color = this.statusAttention,
    isMediaTheme: Boolean = this.isMediaTheme,
    info: Color = this.info,
  ): SkillBillColors = SkillBillColors(
    backgroundVariant = backgroundVariant,
    surfaceVariant = surfaceVariant,
    onSurface = onSurface,
    outlineNormal = outlineNormal,
    outlineDisabled = outlineDisabled,
    onSurfaceInverted = onSurfaceInverted,
    separator = separator,
    positive = positive,
    warning = warning,
    scrim = scrim,
    statusAttention = statusAttention,
    isMediaTheme = isMediaTheme,
    info = info,
  )
}

internal val LocalSkillBillColors = staticCompositionLocalOf { defaultSkillBillColors() }

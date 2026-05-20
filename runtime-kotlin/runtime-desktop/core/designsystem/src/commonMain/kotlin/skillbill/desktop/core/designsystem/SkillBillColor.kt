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

private const val LOW_EMPHASIS_ALPHA = 0.86f
private const val DISABLED_CONTENT_ALPHA = 0.70f

val SkillBillYellow = Color(0xFFF4C430)
internal val SkillBillYellowDeep = Color(0xFFC99717)
internal val SkillBillInk = Color(0xFF0B0B0D)
val SkillBillOnYellow = SkillBillInk

// SKILL-46 follow-up F-609: the colors below were `internal`; widening to module-public so desktop
// surfaces can converge on a single design-system source of truth instead of declaring parallel
// hex constants in feature files.
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
val SkillBillTransparent = Color.Transparent
internal val SkillBillMacGreen = Color(0xFF28C840)
internal val SkillBillHeroGold = Color(0xFFE1AF1D)
internal val SkillBillNodeText = Color(0xFFF5E8AE)
internal val SkillBillLightBackground = Color(0xFFFAF8F1)
internal val SkillBillLightSurface = Color(0xFFFFFFFF)
internal val SkillBillLightSurfaceVariant = Color(0xFFF2EEE2)
internal val SkillBillLightLine = Color(0xFFD6CEBA)
internal val SkillBillLightText = Color(0xFF191711)
internal val SkillBillLightMuted = Color(0xFF665F4E)
internal val SkillBillLightSteel = Color(0xFF4E5A64)
internal val SkillBillLightGreen = Color(0xFF146C43)
internal val SkillBillLightRed = Color(0xFFBA1A1A)
internal val SkillBillLightAmber = Color(0xFF755B00)

object SkillBillBrandColors {
  val Yellow = SkillBillYellow
  val YellowDeep = SkillBillYellowDeep
  val Green = SkillBillGreen
  val Steel = SkillBillSteel
  val SteelDark = SkillBillSteelDark

  val all = listOf(Yellow, YellowDeep, Green, Steel, SteelDark)
}

internal val SkillBillLightColorScheme = lightColorScheme(
  primary = SkillBillLightAmber,
  onPrimary = Color.White,
  primaryContainer = SkillBillYellow,
  onPrimaryContainer = SkillBillInk,
  secondary = SkillBillLightMuted,
  onSecondary = Color.White,
  secondaryContainer = SkillBillLightSurfaceVariant,
  onSecondaryContainer = SkillBillLightText,
  tertiary = SkillBillLightGreen,
  onTertiary = Color.White,
  tertiaryContainer = Color(0xFFD8F6E3),
  onTertiaryContainer = Color(0xFF002112),
  error = SkillBillLightRed,
  onError = Color.White,
  errorContainer = Color(0xFFFFDAD6),
  onErrorContainer = Color(0xFF410002),
  background = SkillBillLightBackground,
  onBackground = SkillBillLightText,
  surface = SkillBillLightSurface,
  onSurface = SkillBillLightText,
  surfaceVariant = SkillBillLightSurfaceVariant,
  onSurfaceVariant = SkillBillLightMuted,
  outline = SkillBillLightLine,
  surfaceTint = SkillBillLightAmber,
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
  tertiary = SkillBillLightSurfaceVariant,
  neutral = SkillBillLightSurface,
)

val SkillBillDarkThemeTokens = SkillBillThemeTokens(
  textField = SkillBillTextFieldTokens(
    text = SkillBillText,
    disabledText = SkillBillText.copy(alpha = DISABLED_CONTENT_ALPHA),
    placeholder = SkillBillMuted.copy(alpha = LOW_EMPHASIS_ALPHA),
    disabledPlaceholder = SkillBillMuted.copy(alpha = DISABLED_CONTENT_ALPHA),
    container = SkillBillPanel,
    disabledContainer = SkillBillPanel.copy(alpha = 0.72f),
    border = SkillBillLine,
    focusedBorder = SkillBillYellow,
    disabledBorder = SkillBillLine.copy(alpha = 0.55f),
    cursor = SkillBillYellow,
  ),
  semanticTones = SkillBillSemanticToneTokens(
    dialog = SkillBillSurfaceTone(
      container = SkillBillPanelRaised,
      content = SkillBillText,
      border = SkillBillLine,
    ),
    scrim = Color.Black.copy(alpha = 0.72f),
    warningBanner = SkillBillSurfaceTone(
      container = Color(0xFF2B2110),
      content = SkillBillAmber,
      border = SkillBillAmber.copy(alpha = 0.42f),
    ),
    successBanner = SkillBillSurfaceTone(
      container = Color(0xFF10291A),
      content = SkillBillGreen,
      border = SkillBillGreen.copy(alpha = 0.42f),
    ),
    errorBanner = SkillBillSurfaceTone(
      container = Color(0xFF301516),
      content = SkillBillRed,
      border = SkillBillRed.copy(alpha = 0.46f),
    ),
  ),
  syntax = SkillBillSyntaxTokens(
    yaml = YamlSyntaxColors(
      comment = SkillBillSteelDark,
      key = SkillBillYellow,
      string = SkillBillGreen,
      marker = SkillBillAmber,
      scalar = SkillBillText.copy(alpha = 0.9f),
    ),
  ),
  diff = SkillBillDiffTokens(
    metadata = SkillBillMuted,
    hunk = SkillBillAmber,
    addition = SkillBillGreen,
    deletion = SkillBillRed,
    context = SkillBillText.copy(alpha = 0.85f),
  ),
  frame = SkillBillFrameTokens(
    background = SkillBillBlack,
    panel = SkillBillPanel,
    raised = SkillBillPanelRaised,
    sidebar = SkillBillFrameColor,
    line = SkillBillLine,
    text = SkillBillText,
    muted = SkillBillMuted,
    subtle = SkillBillSteel,
    primary = SkillBillYellow,
    onPrimary = SkillBillOnYellow,
    transparent = SkillBillTransparent,
    status = SkillBillStatusToneTokens(
      neutral = SkillBillMuted,
      success = SkillBillGreen,
      warning = SkillBillAmber,
      error = SkillBillRed,
    ),
  ),
)

val SkillBillLightThemeTokens = SkillBillThemeTokens(
  textField = SkillBillTextFieldTokens(
    text = SkillBillLightText,
    disabledText = SkillBillLightText.copy(alpha = DISABLED_CONTENT_ALPHA),
    placeholder = SkillBillLightMuted.copy(alpha = LOW_EMPHASIS_ALPHA),
    disabledPlaceholder = SkillBillLightMuted.copy(alpha = DISABLED_CONTENT_ALPHA),
    container = SkillBillLightSurface,
    disabledContainer = SkillBillLightSurfaceVariant.copy(alpha = 0.72f),
    border = SkillBillLightMuted,
    focusedBorder = SkillBillLightAmber,
    disabledBorder = SkillBillLightMuted.copy(alpha = DISABLED_CONTENT_ALPHA),
    cursor = SkillBillLightAmber,
  ),
  semanticTones = SkillBillSemanticToneTokens(
    dialog = SkillBillSurfaceTone(
      container = SkillBillLightSurface,
      content = SkillBillLightText,
      border = SkillBillLightLine,
    ),
    scrim = Color.Black.copy(alpha = 0.42f),
    warningBanner = SkillBillSurfaceTone(
      container = Color(0xFFFFF0C2),
      content = SkillBillLightAmber,
      border = Color(0xFFD9B64A),
    ),
    successBanner = SkillBillSurfaceTone(
      container = Color(0xFFDDF5E6),
      content = SkillBillLightGreen,
      border = Color(0xFF85C89D),
    ),
    errorBanner = SkillBillSurfaceTone(
      container = Color(0xFFFFDAD6),
      content = SkillBillLightRed,
      border = Color(0xFFE88D84),
    ),
  ),
  syntax = SkillBillSyntaxTokens(
    yaml = YamlSyntaxColors(
      comment = SkillBillLightSteel,
      key = SkillBillLightAmber,
      string = SkillBillLightGreen,
      marker = SkillBillLightAmber,
      scalar = SkillBillLightText.copy(alpha = 0.88f),
    ),
  ),
  diff = SkillBillDiffTokens(
    metadata = SkillBillLightMuted,
    hunk = SkillBillLightAmber,
    addition = SkillBillLightGreen,
    deletion = SkillBillLightRed,
    context = SkillBillLightText.copy(alpha = 0.86f),
  ),
  frame = SkillBillFrameTokens(
    background = SkillBillLightBackground,
    panel = SkillBillLightSurface,
    raised = SkillBillLightSurfaceVariant,
    sidebar = SkillBillLightSurfaceVariant,
    line = SkillBillLightLine,
    text = SkillBillLightText,
    muted = SkillBillLightMuted,
    subtle = SkillBillLightSteel,
    primary = SkillBillLightAmber,
    onPrimary = Color.White,
    transparent = SkillBillTransparent,
    status = SkillBillStatusToneTokens(
      neutral = SkillBillLightMuted,
      success = SkillBillLightGreen,
      warning = SkillBillLightAmber,
      error = SkillBillLightRed,
    ),
  ),
)

@Composable
internal fun skillBillOutlinedTextFieldColors(): TextFieldColors {
  val tokens = SkillBillTheme.textFieldTokens
  return OutlinedTextFieldDefaults.colors(
    focusedTextColor = tokens.text,
    unfocusedTextColor = tokens.text,
    disabledTextColor = tokens.disabledText,
    focusedContainerColor = tokens.container,
    unfocusedContainerColor = tokens.container,
    disabledContainerColor = tokens.disabledContainer,
    focusedPlaceholderColor = tokens.placeholder,
    unfocusedPlaceholderColor = tokens.placeholder,
    disabledPlaceholderColor = tokens.disabledPlaceholder,
    focusedBorderColor = tokens.focusedBorder,
    unfocusedBorderColor = tokens.border,
    disabledBorderColor = tokens.disabledBorder,
    cursorColor = tokens.cursor,
  )
}

@Composable
internal fun skillBillTextFieldColors(): TextFieldColors {
  val tokens = SkillBillTheme.textFieldTokens
  return TextFieldDefaults.colors(
    focusedPlaceholderColor = tokens.placeholder,
    unfocusedPlaceholderColor = tokens.placeholder,
    disabledPlaceholderColor = tokens.disabledPlaceholder,
    focusedIndicatorColor = tokens.focusedBorder,
    unfocusedIndicatorColor = tokens.border,
    disabledIndicatorColor = tokens.disabledBorder,
    errorContainerColor = tokens.container,
    errorIndicatorColor = SkillBillTheme.colors.error,
    cursorColor = tokens.cursor,
    focusedTextColor = tokens.text,
    unfocusedTextColor = tokens.text,
    disabledTextColor = tokens.disabledText,
    focusedContainerColor = tokens.container,
    unfocusedContainerColor = tokens.container,
    disabledContainerColor = tokens.disabledContainer,
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

fun defaultSkillBillLightColors(
  backgroundVariant: Color = SkillBillLightBackground,
  surfaceVariant: Color = SkillBillLightSurfaceVariant,
  onSurface: Color = SkillBillLightText,
  outlineNormal: Color = SkillBillLightLine,
  outlineDisabled: Color = SkillBillLightLine.copy(alpha = 0.62f),
  onSurfaceInverted: Color = Color.White,
  separator: Color = SkillBillLightLine,
  scrim: Color = Color.Black.copy(alpha = 0.42f),
  positive: Color = SkillBillLightGreen,
  warning: Color = SkillBillLightAmber,
  statusAttention: Color = SkillBillLightRed,
  info: Color = SkillBillLightSteel,
): SkillBillColors = createSkillBillColors(
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

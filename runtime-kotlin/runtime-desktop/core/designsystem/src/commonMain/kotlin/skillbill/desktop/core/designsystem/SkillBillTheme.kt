@file:Suppress("FunctionName")

package skillbill.desktop.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

@Composable
fun SkillBillMaterialTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  skillBillColors: SkillBillColors = if (darkTheme) defaultSkillBillColors() else defaultSkillBillLightColors(),
  skillBillTokens: SkillBillThemeTokens = if (darkTheme) SkillBillDarkThemeTokens else SkillBillLightThemeTokens,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) SkillBillDarkColorScheme else SkillBillLightColorScheme

  CompositionLocalProvider(
    LocalSkillBillColors provides skillBillColors,
    LocalSkillBillThemeTokens provides skillBillTokens,
    LocalSkillBillGradientColors provides if (darkTheme) GradientColors() else SkillBillLightGradientColors,
  ) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = SkillBillTypography,
      shapes = SkillBillShapes,
      content = content,
    )
  }
}

@Composable
fun SkillBillAppTheme(content: @Composable () -> Unit) {
  SkillBillMaterialTheme(content = content)
}

@Composable
fun SkillBillMediaTheme(content: @Composable () -> Unit) {
  CompositionLocalProvider(
    LocalSkillBillColors provides defaultSkillBillMediaColors(),
    LocalSkillBillThemeTokens provides SkillBillDarkThemeTokens,
    LocalSkillBillGradientColors provides GradientColors(),
  ) {
    MaterialTheme(
      colorScheme = SkillBillDarkColorScheme,
      typography = SkillBillTypography,
      shapes = SkillBillShapes,
      content = content,
    )
  }
}

object SkillBillTheme {
  val colors: ColorScheme
    @Composable get() = MaterialTheme.colorScheme

  val extendedColors: SkillBillColors
    @Composable get() = LocalSkillBillColors.current

  val tokens: SkillBillThemeTokens
    @Composable get() = LocalSkillBillThemeTokens.current

  val textFieldTokens: SkillBillTextFieldTokens
    @Composable get() = tokens.textField

  val semanticTones: SkillBillSemanticToneTokens
    @Composable get() = tokens.semanticTones

  val syntaxTokens: SkillBillSyntaxTokens
    @Composable get() = tokens.syntax

  val diffTokens: SkillBillDiffTokens
    @Composable get() = tokens.diff

  val frameTokens: SkillBillFrameTokens
    @Composable get() = tokens.frame

  val typography: Typography
    @Composable get() = MaterialTheme.typography

  val shapes: Shapes
    @Composable get() = MaterialTheme.shapes

  val backgroundTheme: BackgroundTheme
    @Composable get() = BackgroundTheme(
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 2.dp,
    )

  val gradientColors: GradientColors
    @Composable get() = LocalSkillBillGradientColors.current

  val outlinedTextFieldColors: TextFieldColors
    @Composable get() = skillBillOutlinedTextFieldColors()

  val textFieldColors: TextFieldColors
    @Composable get() = skillBillTextFieldColors()
}

enum class SkillBillSystemBarsTheme {
  Light,
  Dark,
}

internal val LocalSkillBillThemeTokens = staticCompositionLocalOf { SkillBillDarkThemeTokens }
internal val LocalSkillBillGradientColors = staticCompositionLocalOf { GradientColors() }

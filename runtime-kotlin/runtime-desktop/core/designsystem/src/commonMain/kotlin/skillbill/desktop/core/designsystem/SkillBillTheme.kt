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
import androidx.compose.ui.unit.dp

@Composable
fun SkillBillMaterialTheme(
  skillBillColors: SkillBillColors = defaultSkillBillColors(),
  content: @Composable () -> Unit,
) {
  val darkTheme = isSystemInDarkTheme()
  val colorScheme = if (darkTheme) SkillBillDarkColorScheme else SkillBillLightColorScheme

  CompositionLocalProvider(LocalSkillBillColors provides skillBillColors) {
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
  CompositionLocalProvider(LocalSkillBillColors provides defaultSkillBillMediaColors()) {
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
    @Composable get() = if (isSystemInDarkTheme()) GradientColors() else SkillBillLightGradientColors

  val outlinedTextFieldColors: TextFieldColors
    @Composable get() = skillBillOutlinedTextFieldColors()

  val textFieldColors: TextFieldColors
    @Composable get() = skillBillTextFieldColors()
}

enum class SkillBillSystemBarsTheme {
  Light,
  Dark,
}

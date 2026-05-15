package skillbill.desktop.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class GradientColors(
  val primary: Color = Color.Unspecified,
  val secondary: Color = Color.Unspecified,
  val tertiary: Color = Color.Unspecified,
  val neutral: Color = Color.Unspecified,
)

package skillbill.desktop.core.designsystem

import androidx.compose.runtime.Composable

object SkillBillContentAlpha {
  val highEmphasis: Float
    @Composable get() = contentAlpha(1.0f)

  val mediumEmphasis: Float
    @Composable get() = contentAlpha(0.6f)

  val lowEmphasis: Float
    @Composable get() = contentAlpha(0.38f)

  val veryLowEmphasis: Float
    @Composable get() = contentAlpha(0.1f)

  @Composable
  private fun contentAlpha(normalAlpha: Float, mediaThemeAlpha: Float = normalAlpha): Float {
    val mediaTheme = SkillBillTheme.extendedColors.isMediaTheme
    return if (mediaTheme) mediaThemeAlpha else normalAlpha
  }
}

@file:Suppress("FunctionName")

package skillbill.desktop.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import skillbill.desktop.core.designsystem.SkillBillDimens
import skillbill.desktop.core.designsystem.SkillBillTheme

@Composable
fun SkillBillWindow(content: @Composable () -> Unit) {
  val colors = SkillBillTheme.extendedColors
  androidx.compose.foundation.layout.Box(
    modifier =
    Modifier
      .fillMaxSize()
      .background(SkillBillTheme.colors.background)
      .border(SkillBillDimens.hairline, colors.separator),
  ) {
    content()
  }
}

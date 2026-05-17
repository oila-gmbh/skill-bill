@file:Suppress("FunctionName", "MagicNumber")

package skillbill.desktop.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import skillbill.desktop.core.designsystem.SkillBillTheme

@Composable
fun SkillBillWindow(content: @Composable () -> Unit) {
  val colors = SkillBillTheme.extendedColors
  androidx.compose.foundation.layout.Box(
    modifier =
    Modifier
      .fillMaxSize()
      .background(SkillBillTheme.colors.background)
      .border(1.dp, colors.separator),
  ) {
    content()
  }
}

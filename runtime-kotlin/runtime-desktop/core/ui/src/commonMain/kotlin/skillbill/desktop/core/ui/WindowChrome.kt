@file:Suppress("FunctionName")

package skillbill.desktop.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import skillbill.desktop.core.designsystem.SkillBillTheme

@Composable
fun SkillBillWindow(title: String, content: @Composable () -> Unit) {
  val colors = SkillBillTheme.extendedColors
  Column(
    modifier =
    Modifier
      .fillMaxSize()
      .background(SkillBillTheme.colors.background)
      .border(1.dp, colors.separator),
  ) {
    Text(
      text = title,
      modifier =
      Modifier
        .fillMaxWidth()
        .height(28.dp)
        .background(SkillBillTheme.colors.surfaceVariant),
      color = SkillBillTheme.colors.primary,
      fontWeight = FontWeight.Bold,
    )
    content()
  }
}

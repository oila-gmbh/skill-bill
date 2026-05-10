@file:Suppress("FunctionName")

package skillbill.desktop.core.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import skillbill.desktop.core.designsystem.WorkbenchPalette

@Composable
fun WorkbenchWindow(title: String, content: @Composable () -> Unit) {
  Column(modifier = Modifier.fillMaxSize().border(2.dp, WorkbenchPalette.rule)) {
    Text(
      text = title,
      modifier = Modifier.fillMaxWidth().height(24.dp),
      color = WorkbenchPalette.selectionText,
      fontWeight = FontWeight.Bold,
    )
    content()
  }
}

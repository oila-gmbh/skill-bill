@file:Suppress("FunctionName")

package skillbill.desktop.core.designsystem

import androidx.compose.material.MaterialTheme
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object WorkbenchPalette {
  val chrome = Color(0xFFBDBDBD)
  val canvas = Color(0xFFC0C0C0)
  val sidebar = Color(0xFFE3E3E3)
  val editor = Color(0xFFFFFFFF)
  val status = Color(0xFFE8E8E8)
  val text = Color(0xFF111111)
  val mutedText = Color(0xFF4F4F4F)
  val accent = Color(0xFF000080)
  val rule = Color(0xFF808080)
  val selection = Color(0xFF000080)
  val selectionText = Color(0xFFFFFFFF)
}

object WorkbenchMetrics {
  val toolbarHeight = 72.dp
  val treePaneWidth = 280.dp
  val statusPaneHeight = 84.dp
  val treeIndent = 18.dp
}

@Composable
fun SkillBillWorkbenchTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colors =
    lightColors(
      primary = WorkbenchPalette.accent,
      primaryVariant = WorkbenchPalette.accent,
      secondary = WorkbenchPalette.rule,
      background = WorkbenchPalette.canvas,
      surface = WorkbenchPalette.editor,
      onPrimary = WorkbenchPalette.selectionText,
      onSecondary = WorkbenchPalette.text,
      onBackground = WorkbenchPalette.text,
      onSurface = WorkbenchPalette.text,
    ),
    content = content,
  )
}

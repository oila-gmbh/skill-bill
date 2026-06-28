package skillbill.desktop.core.designsystem

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object SkillBillTypeStyles {
  val code = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
  val body125 = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
  val body13 = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
  val mono13 = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
  val semiBoldLabel = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
  val monoBadge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
  val caption = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium)
  val codeCaption = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
  val microLabel = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 8.sp, fontWeight = FontWeight.Bold)
  val bodySmallNormal = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
}

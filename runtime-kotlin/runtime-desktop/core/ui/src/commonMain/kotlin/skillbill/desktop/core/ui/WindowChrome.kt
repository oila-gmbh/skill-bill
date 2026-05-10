@file:Suppress("FunctionName", "MagicNumber")

package skillbill.desktop.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import skillbill.desktop.core.designsystem.SkillBillTheme

private val ChromePanel = Color(0xFF121216)
private val ChromeRaised = Color(0xFF15151A)
private val ChromeLine = Color(0xFF2A2A31)
private val ChromeText = Color(0xFFF6F3E7)
private val ChromeMuted = Color(0xFFB7B1A0)
private val ChromeSteel = Color(0xFF6F7882)
private val ChromeYellow = Color(0xFFF4C430)
private val ChromeRed = Color(0xFFFF5F57)
private val ChromeAmber = Color(0xFFFFBD2E)
private val ChromeGreen = Color(0xFF60D394)

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
    TitleBar(title = title)
    content()
  }
}

@Composable
private fun TitleBar(title: String) {
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(36.dp)
      .background(ChromePanel)
      .border(1.dp, ChromeLine)
      .padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    WindowDots()
    Row(
      modifier = Modifier.padding(start = 16.dp).weight(1f),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box(
        modifier =
        Modifier
          .size(18.dp)
          .clip(RoundedCornerShape(3.dp))
          .background(ChromeYellow),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = "SB",
          color = Color(0xFF0B0B0D),
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
        )
      }
      Text(text = title, color = ChromeText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
      CrumbDivider()
      Crumb("acme-ai-platform")
      CrumbDivider()
      Text(
        text = "skills/meeting-summarizer",
        color = ChromeText,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = "⌘K  Quick action",
        color = ChromeMuted,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier =
        Modifier
          .border(1.dp, ChromeLine, RoundedCornerShape(4.dp))
          .background(ChromeRaised, RoundedCornerShape(4.dp))
          .padding(horizontal = 8.dp, vertical = 2.dp),
      )
      Text(text = "st", color = ChromeSteel, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
  }
}

@Composable
private fun WindowDots() {
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Dot(ChromeRed)
    Dot(ChromeAmber)
    Dot(ChromeGreen)
  }
}

@Composable
private fun Dot(color: Color) {
  Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun Crumb(text: String) {
  Text(text = text, color = ChromeMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun CrumbDivider() {
  Text(text = "/", color = ChromeSteel, fontSize = 12.sp)
}

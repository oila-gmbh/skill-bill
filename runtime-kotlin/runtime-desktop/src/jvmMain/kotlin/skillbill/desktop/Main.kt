package skillbill.desktop

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.skia.Image
import skillbill.desktop.app.SkillBillDesktopApp

fun main() {
  val uiScaleConfiguration = resolveDesktopUiScale()
  val component = createJvmApplicationComponent()
  val userComponentManager = component.desktopUserComponentManager
  val appIcon = loadAppIcon()

  application {
    val windowState = rememberWindowState(
      width = (1600 * uiScaleConfiguration.windowScale).dp,
      height = (920 * uiScaleConfiguration.windowScale).dp,
    )
    Window(
      onCloseRequest = ::exitApplication,
      state = windowState,
      title = "SkillBill",
      icon = appIcon,
    ) {
      SkillBillDesktopApp(
        userComponentManager = userComponentManager,
        contentDensity = uiScaleConfiguration.contentDensity,
      )
    }
  }
}

private fun loadAppIcon(): BitmapPainter? {
  val bytes = object {}.javaClass.getResourceAsStream("/icon.png")?.use { it.readBytes() } ?: return null
  return BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
}

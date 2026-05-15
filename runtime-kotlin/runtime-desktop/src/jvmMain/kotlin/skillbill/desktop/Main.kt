package skillbill.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import skillbill.desktop.app.SkillBillDesktopApp

fun main() {
  val uiScaleConfiguration = resolveDesktopUiScale()
  val component = createJvmApplicationComponent()
  val userComponentManager = component.desktopUserComponentManager

  application {
    val windowState = rememberWindowState(
      width = (1600 * uiScaleConfiguration.windowScale).dp,
      height = (920 * uiScaleConfiguration.windowScale).dp,
    )
    Window(
      onCloseRequest = ::exitApplication,
      state = windowState,
      title = "SkillBill",
    ) {
      SkillBillDesktopApp(
        userComponentManager = userComponentManager,
        contentDensity = uiScaleConfiguration.contentDensity,
      )
    }
  }
}

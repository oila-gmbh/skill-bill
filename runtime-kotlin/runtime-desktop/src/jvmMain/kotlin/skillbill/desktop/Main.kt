package skillbill.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import skillbill.desktop.app.SkillBillDesktopApp

fun main() {
  val component = createJvmApplicationComponent()
  val userComponentManager = component.desktopUserComponentManager

  application {
    Window(
      onCloseRequest = ::exitApplication,
      title = "Skill Bill",
    ) {
      SkillBillDesktopApp(userComponentManager = userComponentManager)
    }
  }
}

@file:Suppress("FunctionName")

package skillbill.desktop.app

import androidx.compose.runtime.Composable
import skillbill.desktop.app.di.DesktopUserComponentManager
import skillbill.desktop.core.designsystem.SkillBillWorkbenchTheme
import skillbill.desktop.core.ui.WorkbenchWindow
import skillbill.desktop.feature.workbench.ui.WorkbenchRoute

@Composable
fun SkillBillDesktopApp(userComponentManager: DesktopUserComponentManager) {
  val userComponent = userComponentManager.userComponent ?: userComponentManager.createComponent()

  SkillBillWorkbenchTheme {
    WorkbenchWindow(title = "Skill Bill Workbench") {
      WorkbenchRoute(componentFactory = userComponent.workbenchComponentFactory)
    }
  }
}

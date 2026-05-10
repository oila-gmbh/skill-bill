package skillbill.desktop.app.di

import skillbill.desktop.core.domain.di.UserDependencies
import skillbill.desktop.feature.workbench.di.WorkbenchComponentFactory

interface DesktopUserComponent : UserDependencies {
  val workbenchComponentFactory: WorkbenchComponentFactory
}

interface DesktopUserComponentManager {
  val userComponent: DesktopUserComponent?

  fun createComponent(): DesktopUserComponent
  fun destroyComponent()
}

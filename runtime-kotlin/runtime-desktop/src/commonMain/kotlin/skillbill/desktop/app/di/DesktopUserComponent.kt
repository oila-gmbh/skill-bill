package skillbill.desktop.app.di

import skillbill.desktop.core.domain.di.UserComponentManager
import skillbill.desktop.core.domain.di.UserDependencies
import skillbill.desktop.core.ui.di.ScreenComponentFactoryOwner
import skillbill.desktop.feature.skillbill.di.SkillBillComponentFactory

interface DesktopUserComponent : UserDependencies, ScreenComponentFactoryOwner {
  override val screenComponentFactory: SkillBillComponentFactory
}

interface DesktopUserComponentManager : UserComponentManager {
  override val userComponent: DesktopUserComponent?

  fun createComponent(): DesktopUserComponent
}

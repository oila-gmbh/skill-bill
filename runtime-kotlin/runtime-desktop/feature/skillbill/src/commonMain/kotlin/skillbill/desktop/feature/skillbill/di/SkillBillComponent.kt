package skillbill.desktop.feature.skillbill.di

import skillbill.desktop.core.common.di.DispatcherProvider
import skillbill.desktop.core.common.di.ScreenScope
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.ui.di.ScreenComponent
import skillbill.desktop.core.ui.di.ScreenComponentFactory
import skillbill.desktop.feature.skillbill.state.SkillBillViewModel
import software.amazon.lastmile.kotlin.inject.anvil.ContributesSubcomponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@ContributesSubcomponent(ScreenScope::class)
@SingleIn(ScreenScope::class)
interface SkillBillComponent : ScreenComponent {
  val dispatcherProvider: DispatcherProvider
  val viewModel: SkillBillViewModel

  @ContributesSubcomponent.Factory(UserScope::class)
  interface Factory : SkillBillComponentFactory {
    override fun create(): SkillBillComponent
  }
}

interface SkillBillComponentFactory : ScreenComponentFactory {
  override fun create(): SkillBillComponent
}

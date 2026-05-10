package skillbill.desktop.feature.workbench.di

import skillbill.desktop.core.common.di.ScreenScope
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.feature.workbench.state.WorkbenchViewModel
import software.amazon.lastmile.kotlin.inject.anvil.ContributesSubcomponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@ContributesSubcomponent(ScreenScope::class)
@SingleIn(ScreenScope::class)
interface WorkbenchComponent {
  val viewModel: WorkbenchViewModel

  @ContributesSubcomponent.Factory(UserScope::class)
  interface Factory : WorkbenchComponentFactory {
    override fun create(): WorkbenchComponent
  }
}

interface WorkbenchComponentFactory {
  fun create(): WorkbenchComponent
}

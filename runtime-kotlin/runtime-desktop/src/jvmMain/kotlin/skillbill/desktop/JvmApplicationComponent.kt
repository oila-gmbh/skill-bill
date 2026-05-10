package skillbill.desktop

import me.tatarka.inject.annotations.Provides
import skillbill.desktop.app.di.DesktopUserComponentManager
import skillbill.desktop.core.common.di.AppScope
import skillbill.desktop.core.common.di.DefaultDispatcherProvider
import skillbill.desktop.core.common.di.DispatcherProvider
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@MergeComponent(AppScope::class)
@SingleIn(AppScope::class)
abstract class JvmApplicationComponent {
  abstract val userComponentFactory: JvmUserComponent.Factory
  abstract val userComponentManager: JvmUserComponentManager

  val desktopUserComponentManager: DesktopUserComponentManager
    get() = userComponentManager

  @Provides
  fun DefaultDispatcherProvider.bindDispatcherProvider(): DispatcherProvider = this
}

fun createJvmApplicationComponent(): JvmApplicationComponent = JvmApplicationComponent::class.create()

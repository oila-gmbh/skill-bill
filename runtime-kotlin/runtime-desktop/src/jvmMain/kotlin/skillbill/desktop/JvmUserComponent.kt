package skillbill.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.tatarka.inject.annotations.Inject
import skillbill.desktop.app.di.DesktopUserComponent
import skillbill.desktop.app.di.DesktopUserComponentManager
import skillbill.desktop.core.common.di.AppScope
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.domain.di.UserComponentManager
import skillbill.desktop.core.domain.di.UserDependencies
import skillbill.desktop.core.domain.model.UserSession
import skillbill.desktop.feature.skillbill.di.SkillBillComponent
import software.amazon.lastmile.kotlin.inject.anvil.ContributesSubcomponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@ContributesSubcomponent(UserScope::class)
@SingleIn(UserScope::class)
interface JvmUserComponent : DesktopUserComponent {
  override val userSession: UserSession
  override val screenComponentFactory: SkillBillComponent.Factory

  @ContributesSubcomponent.Factory(AppScope::class)
  interface Factory {
    fun create(userSession: UserSession): JvmUserComponent
  }
}

@Inject
@SingleIn(AppScope::class)
class JvmUserComponentManager(private val userComponentFactory: JvmUserComponent.Factory) :
  DesktopUserComponentManager,
  UserComponentManager {
  private val userState = MutableStateFlow<DesktopUserComponent?>(null)

  override val userComponent: DesktopUserComponent?
    get() = userState.value

  override val userComponentFlow: StateFlow<UserDependencies?>
    get() = userState

  override fun createComponent(): DesktopUserComponent = createComponent(
    UserSession.localDesktop,
  ) as DesktopUserComponent

  override fun createComponent(userSession: UserSession): UserDependencies {
    val existing = userState.value
    if (existing != null) {
      return existing
    }

    val component = userComponentFactory.create(userSession)
    userState.value = component
    return component
  }

  override fun destroyComponent() {
    userState.value = null
  }
}

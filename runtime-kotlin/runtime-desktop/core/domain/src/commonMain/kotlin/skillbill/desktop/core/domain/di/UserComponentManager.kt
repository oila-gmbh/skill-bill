package skillbill.desktop.core.domain.di

import kotlinx.coroutines.flow.StateFlow
import skillbill.desktop.core.domain.model.UserSession

interface UserDependencies {
  val userSession: UserSession
}

interface UserComponentManager {
  val userComponent: UserDependencies?
  val userComponentFlow: StateFlow<UserDependencies?>

  fun createComponent(userSession: UserSession): UserDependencies
  fun destroyComponent()
}

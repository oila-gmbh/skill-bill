package skillbill.desktop.app.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import skillbill.desktop.core.domain.di.UserComponentManager
import skillbill.desktop.core.domain.di.UserDependencies
import skillbill.desktop.core.domain.model.UserSession
import skillbill.desktop.core.navigation.DesktopNavigator

@Composable
fun rememberSkillBillDesktopAppState(userComponentManager: UserComponentManager): SkillBillDesktopAppState {
  val userComponent by userComponentManager.userComponentFlow.collectAsState()
  val state =
    remember(userComponentManager) {
      SkillBillDesktopAppState(userComponentManager = userComponentManager)
    }

  LaunchedEffect(userComponent) {
    state.ensureLocalSession(userComponent)
  }

  return state
}

class SkillBillDesktopAppState(private val userComponentManager: UserComponentManager) {
  val navigator: DesktopNavigator = DesktopNavigator()

  fun ensureLocalSession(userComponent: UserDependencies?) {
    if (userComponent == null) {
      userComponentManager.createComponent(UserSession.localDesktop)
    }
  }
}

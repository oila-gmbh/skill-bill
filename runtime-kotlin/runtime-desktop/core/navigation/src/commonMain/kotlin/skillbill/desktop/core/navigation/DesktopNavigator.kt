package skillbill.desktop.core.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class DesktopNavigator(initialRoute: DesktopRoute = SkillBillHomeRoute) {
  private val mutableState = MutableStateFlow(DesktopNavigationState(listOf(initialRoute)))

  val state: StateFlow<DesktopNavigationState>
    get() = mutableState

  fun navigate(route: DesktopRoute) {
    mutableState.update { currentState ->
      if (currentState.currentRoute == route) {
        currentState
      } else {
        currentState.copy(backStack = currentState.backStack + route)
      }
    }
  }

  fun replaceRoot(route: DesktopRoute) {
    mutableState.value = DesktopNavigationState(listOf(route))
  }

  fun goBack(): Boolean {
    var didGoBack = false
    mutableState.update { currentState ->
      if (currentState.canGoBack) {
        didGoBack = true
        currentState.copy(backStack = currentState.backStack.dropLast(1))
      } else {
        currentState
      }
    }
    return didGoBack
  }

  fun reset() {
    replaceRoot(SkillBillHomeRoute)
  }
}

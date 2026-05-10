@file:Suppress("FunctionName")

package skillbill.desktop.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import skillbill.desktop.app.di.DesktopUserComponentManager
import skillbill.desktop.app.state.rememberSkillBillDesktopAppState
import skillbill.desktop.core.designsystem.SkillBillAppTheme
import skillbill.desktop.core.navigation.SkillBillHomeRoute
import skillbill.desktop.core.navigation.SkillBillSourceRoute
import skillbill.desktop.core.ui.SkillBillWindow
import skillbill.desktop.core.ui.di.LocalUserComponentManager
import skillbill.desktop.core.ui.di.ProvideScreenComponentFactory
import skillbill.desktop.feature.skillbill.ui.SkillBillRoute

@Composable
fun SkillBillDesktopApp(userComponentManager: DesktopUserComponentManager) {
  val appState = rememberSkillBillDesktopAppState(userComponentManager = userComponentManager)
  val navigationState by appState.navigator.state.collectAsState()

  CompositionLocalProvider(LocalUserComponentManager provides userComponentManager) {
    ProvideScreenComponentFactory {
      SkillBillAppTheme {
        SkillBillWindow(title = "SkillBill") {
          when (navigationState.currentRoute) {
            SkillBillHomeRoute -> SkillBillRoute(
              selectedSourceId = null,
              canNavigateBack = navigationState.canGoBack,
              onNavigateBack = appState.navigator::goBack,
              onSourceRouteSelected = { sourceId ->
                appState.navigator.navigate(SkillBillSourceRoute(sourceId))
              },
            )
            is SkillBillSourceRoute -> {
              val route = navigationState.currentRoute as SkillBillSourceRoute
              SkillBillRoute(
                selectedSourceId = route.sourceId,
                canNavigateBack = navigationState.canGoBack,
                onNavigateBack = appState.navigator::goBack,
                onSourceRouteSelected = { sourceId ->
                  appState.navigator.navigate(SkillBillSourceRoute(sourceId))
                },
              )
            }
          }
        }
      }
    }
  }
}

@file:Suppress("FunctionName")

package skillbill.desktop.core.ui.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
fun ProvideScreenComponentFactory(content: @Composable () -> Unit) {
  val userComponentManager = LocalUserComponentManager.current
  val userComponent by userComponentManager.userComponentFlow.collectAsState()
  val factory: ScreenComponentFactory? = (userComponent as? ScreenComponentFactoryOwner)?.screenComponentFactory
  if (factory == null) {
    return
  }

  CompositionLocalProvider(LocalScreenComponentFactory provides factory) {
    content()
  }
}

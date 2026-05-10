package skillbill.desktop.core.ui.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

interface ScreenComponent

interface ScreenComponentFactory {
  fun create(): ScreenComponent
}

interface ScreenComponentFactoryOwner {
  val screenComponentFactory: ScreenComponentFactory
}

@Composable
inline fun <reified T : ScreenComponent> rememberScreenComponent(): T {
  val factory =
    LocalScreenComponentFactory.current
      ?: error("ScreenComponentFactory not provided")
  return remember(factory) { factory.create() as T }
}

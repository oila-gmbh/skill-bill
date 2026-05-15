package skillbill.desktop.core.ui.di

import androidx.compose.runtime.staticCompositionLocalOf
import skillbill.desktop.core.domain.di.UserComponentManager

val LocalUserComponentManager = staticCompositionLocalOf<UserComponentManager> {
  error("UserComponentManager not provided")
}

val LocalScreenComponentFactory = staticCompositionLocalOf<ScreenComponentFactory?> {
  null
}

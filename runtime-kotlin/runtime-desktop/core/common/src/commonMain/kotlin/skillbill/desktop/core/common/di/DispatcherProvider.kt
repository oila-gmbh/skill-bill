package skillbill.desktop.core.common.di

import kotlinx.coroutines.CoroutineDispatcher

interface DispatcherProvider {
  val default: CoroutineDispatcher
  val io: CoroutineDispatcher
}

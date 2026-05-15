package skillbill.desktop.core.common.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

interface DispatcherProvider {
  val default: CoroutineDispatcher
  val io: CoroutineDispatcher
}

@Inject
@SingleIn(AppScope::class)
class DefaultDispatcherProvider : DispatcherProvider {
  override val default: CoroutineDispatcher = Dispatchers.Default
  override val io: CoroutineDispatcher = Dispatchers.Default
}

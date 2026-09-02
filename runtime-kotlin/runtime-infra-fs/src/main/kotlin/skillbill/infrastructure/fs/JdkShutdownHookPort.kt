package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.process.ShutdownHookPort
import skillbill.ports.process.ShutdownHookRegistration

@Inject
class JdkShutdownHookPort : ShutdownHookPort {
  override fun register(action: () -> Unit): ShutdownHookRegistration {
    val hook = Thread(action)
    val registered = runCatching { Runtime.getRuntime().addShutdownHook(hook) }.isSuccess
    return JdkShutdownHookRegistration(hook, registered)
  }

  private class JdkShutdownHookRegistration(
    private val hook: Thread,
    private val registered: Boolean,
  ) : ShutdownHookRegistration {
    override fun unregister(): Boolean =
      registered && runCatching { Runtime.getRuntime().removeShutdownHook(hook) }.isSuccess
  }
}

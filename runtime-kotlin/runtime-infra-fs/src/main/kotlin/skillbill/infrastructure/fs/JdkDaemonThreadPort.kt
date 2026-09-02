package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.process.DaemonThreadPort

@Inject
class JdkDaemonThreadPort : DaemonThreadPort {
  override fun runWithJoinBudget(action: () -> Unit, joinBudgetMillis: Long) {
    val worker = Thread(action)
    worker.isDaemon = true
    runCatching {
      worker.start()
      worker.join(joinBudgetMillis)
    }
  }
}

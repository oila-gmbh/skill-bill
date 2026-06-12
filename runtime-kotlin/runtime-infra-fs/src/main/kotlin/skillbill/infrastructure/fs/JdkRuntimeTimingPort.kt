package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.time.model.RuntimeWaitResult
import kotlin.time.Duration

@Inject
class JdkRuntimeTimingPort : RuntimeTimingPort {
  override fun wait(duration: Duration): RuntimeWaitResult = try {
    Thread.sleep(duration.inWholeMilliseconds)
    RuntimeWaitResult.COMPLETED
  } catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    RuntimeWaitResult.INTERRUPTED
  }
}

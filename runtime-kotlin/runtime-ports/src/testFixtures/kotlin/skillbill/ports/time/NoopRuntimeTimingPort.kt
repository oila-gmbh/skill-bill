package skillbill.ports.time

import skillbill.ports.time.model.RuntimeWaitResult
import kotlin.time.Duration

object NoopRuntimeTimingPort : RuntimeTimingPort {
  override fun wait(duration: Duration): RuntimeWaitResult {
    return RuntimeWaitResult.COMPLETED
  }
}

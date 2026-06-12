package skillbill.ports.time

import skillbill.ports.time.model.RuntimeWaitResult
import kotlin.time.Duration

fun interface RuntimeTimingPort {
  fun wait(duration: Duration): RuntimeWaitResult
}

object NoopRuntimeTimingPort : RuntimeTimingPort {
  override fun wait(duration: Duration): RuntimeWaitResult = RuntimeWaitResult.COMPLETED
}

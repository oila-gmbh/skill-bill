package skillbill.infrastructure.fs

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

private const val CAP = 3
private const val UNIT_COUNT = 9
private const val TIMEOUT_SECONDS = 30L

class JdkBoundedWorkFanOutPortTest {
  @Test
  fun `more units than the cap run bounded and return one result per input in input order`() {
    val port = JdkBoundedWorkFanOutPort()
    val capReached = CountDownLatch(CAP)
    val inFlight = AtomicInteger()
    val peak = AtomicInteger()
    val units = (1..UNIT_COUNT).map { index ->
      {
        peak.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
        capReached.countDown()
        val ranTogether = capReached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        inFlight.decrementAndGet()
        check(ranTogether) { "the pool never ran $CAP units at the same time" }
        index
      }
    }

    val results = port.runBounded(CAP, units)

    assertEquals((1..UNIT_COUNT).toList(), results.map { it.getOrThrow() })
    assertEquals(CAP, peak.get(), "no more than $CAP units may be in flight at once")
  }

  @Test
  fun `a unit that raises fails at its own index while its peers still return values`() {
    val port = JdkBoundedWorkFanOutPort()
    val failure = IllegalStateException("second unit could not run")
    val units = listOf<() -> String>({ "first" }, { throw failure }, { "third" })

    val results = port.runBounded(CAP, units)

    assertEquals("first", results[0].getOrThrow())
    assertSame(failure, results[1].exceptionOrNull())
    assertEquals("third", results[2].getOrThrow())
  }

  @Test
  fun `a cancelled fan-out leaves no peer running once the caller has unwound`() {
    val port = JdkBoundedWorkFanOutPort()
    val peerStarted = CountDownLatch(1)
    val peerStillRunning = AtomicBoolean(true)
    val units = listOf<() -> String>(
      {
        check(peerStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "the peer unit never started" }
        throw CancellationException("the caller unwound mid-wave")
      },
      {
        peerStarted.countDown()
        try {
          Thread.sleep(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        } finally {
          peerStillRunning.set(false)
        }
        "peer"
      },
    )

    assertFailsWith<CancellationException> { port.runBounded(CAP, units) }

    assertFalse(peerStillRunning.get(), "an abandoned peer must not keep running past the caller's unwind")
  }
}

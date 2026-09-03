package skillbill.ports.concurrency

import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs independent work units with a bounded number of them in flight, and serialises access to
 * whatever sinks those units share.
 *
 * [runBounded] returns exactly one result per input, in input order, and never has more than
 * `maxInFlight` units running at once. A unit that raises is captured as a failed result at its own
 * index; peers are neither cancelled nor discarded, so the caller always sees every unit's verdict
 * and can pick a deterministic one from input order. Cancellation is the one exception: when a unit
 * cancels the fan-out, [runBounded] interrupts its peers and waits for them to stop before it
 * rethrows, so no abandoned unit keeps writing into a sink the caller has already unwound past.
 *
 * [runExclusively] serialises its action against every other [runExclusively] call on the same
 * port, so a sink written by concurrent units stays line-atomic. It belongs here because this port
 * is what creates the concurrency in the first place; the action must stay short and must never
 * wrap a whole unit.
 */
interface BoundedWorkFanOutPort {
  fun <T> runBounded(maxInFlight: Int, units: List<() -> T>): List<Result<T>>

  fun <T> runExclusively(action: () -> T): T
}

object SequentialBoundedWorkFanOutPort : BoundedWorkFanOutPort {
  override fun <T> runBounded(maxInFlight: Int, units: List<() -> T>): List<Result<T>> = units.map { unit ->
    val result = runCatching { unit() }
    (result.exceptionOrNull() as? CancellationException)?.let { throw it }
    result
  }

  override fun <T> runExclusively(action: () -> T): T = action()
}

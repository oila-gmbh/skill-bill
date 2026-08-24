package skillbill.infrastructure.fs

import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingDiagnostics : RuntimeDiagnostics {
  val warnings = CopyOnWriteArrayList<String>()
  val errors = CopyOnWriteArrayList<String>()

  override fun warning(message: String, error: Throwable?) {
    warnings += message
  }

  override fun error(message: String, error: Throwable?) {
    errors += message
  }
}

class JdkFeatureTaskRuntimeWorkerSupervisorTest {
  @Test
  fun `worker from a previous boot on this host is not running`() {
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor()
    val current = supervisor.currentProcess()
    val ownership = FeatureTaskRuntimeWorkerOwnership(
      workflowId = "wftr-test",
      generation = 1,
      ownerToken = "owner-token-0001",
      hostIdentity = current.hostIdentity,
      bootIdentity = "previous-${current.bootIdentity}",
      pid = current.pid,
      processBirthToken = current.processBirthToken,
      leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
      heartbeatAt = "2026-07-14T10:00:00Z",
      expiresAt = "2026-07-14T10:00:30Z",
      phaseId = "implement",
      phaseAttempt = 1,
    )

    assertEquals(FeatureTaskRuntimeProcessInspection.NotRunning, supervisor.inspect(ownership))
  }

  @Test
  fun `a live worker whose stored boot identity came from the drifting fallback still reads live`() {
    // The replaced fallback derived this value from the calling process's start instant minus its
    // accumulated CPU time, so it moved as a process ran. Rows written by it are still in durable
    // state, and treating one as a boot mismatch reported the live owner as gone: `goal stop` then
    // found no live lease and left the runner running, and a second runner could reclaim the lease.
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor()
    val current = supervisor.currentProcess()

    val ownership = ownershipFor(current, bootIdentity = "fallback-1787579295")

    assertEquals(FeatureTaskRuntimeProcessInspection.ExactLive, supervisor.inspect(ownership))
  }

  @Test
  fun `a live worker with no known boot identity still reads live`() {
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor()
    val current = supervisor.currentProcess()

    val ownership = ownershipFor(current, bootIdentity = "boot-identity-unavailable")

    assertEquals(FeatureTaskRuntimeProcessInspection.ExactLive, supervisor.inspect(ownership))
  }

  @Test
  fun `a reused pid is still caught when the boot check abstains`() {
    // With the boot comparison abstaining, process-birth evidence is the whole reused-pid guard.
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor()
    val current = supervisor.currentProcess()

    val ownership = ownershipFor(
      current,
      bootIdentity = "boot-identity-unavailable",
      processBirthToken = (current.processBirthToken.toLong() - 1).toString(),
    )

    assertTrue(
      supervisor.inspect(ownership) is FeatureTaskRuntimeProcessInspection.OwnershipMismatch,
      "a different process occupying the pid must not read as the original worker",
    )
  }

  @Test
  fun `boot identity is a property of the machine's boot, not of the calling process`() {
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor()
    val kernelBootId = Path.of("/proc/sys/kernel/random/boot_id")
    val expected = if (Files.isReadable(kernelBootId)) {
      Files.readString(kernelBootId).trim()
    } else {
      "boot-" + ProcessHandle.of(1).flatMap { it.info().startInstant() }.orElseThrow().toEpochMilli()
    }

    assertEquals(expected, supervisor.currentProcess().bootIdentity)
    assertEquals(
      supervisor.currentProcess().bootIdentity,
      supervisor.currentProcess().bootIdentity,
      "a second reading in the same process must not drift",
    )
  }

  private fun ownershipFor(
    current: skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity,
    bootIdentity: String,
    processBirthToken: String = current.processBirthToken,
  ) = FeatureTaskRuntimeWorkerOwnership(
    workflowId = "wftr-test",
    generation = 1,
    ownerToken = "owner-token-0001",
    hostIdentity = current.hostIdentity,
    bootIdentity = bootIdentity,
    pid = current.pid,
    processBirthToken = processBirthToken,
    leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
    heartbeatAt = "2026-07-14T10:00:00Z",
    expiresAt = "2026-07-14T10:00:30Z",
    phaseId = "implement",
    phaseAttempt = 1,
  )

  @Test
  fun `a throwing tick is absorbed and lease renewal keeps ticking`() {
    val diagnostics = RecordingDiagnostics()
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor(diagnostics)
    val ticks = AtomicInteger()
    val renewedAfterFailure = CountDownLatch(2)

    val heartbeat = supervisor.startHeartbeat(plan()) {
      if (ticks.incrementAndGet() == 1) error("database is locked")
      renewedAfterFailure.countDown()
      FeatureTaskRuntimeHeartbeatTick.Renewed
    }

    try {
      assertTrue(
        renewedAfterFailure.await(TICK_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        "a tick that threw must not end lease renewal, but only $ticks ticks ran",
      )
    } finally {
      heartbeat.stop()
    }
    assertNull(heartbeat.fencingLostReason(), "a transient failure is not a fencing loss")
    val warning = assertSingle(diagnostics.warnings)
    assertTrue(warning.contains("wftr-label"), "the warning must name the lease: $warning")
    assertTrue(warning.contains("consecutive failures=1"), "the warning must count failures: $warning")
    assertTrue(diagnostics.errors.isEmpty(), "a single transient failure must not escalate")
  }

  @Test
  fun `renewal failures that outlast the lease escalate to an error`() {
    val diagnostics = RecordingDiagnostics()
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor(diagnostics)
    val escalated = CountDownLatch(1)

    val heartbeat = supervisor.startHeartbeat(plan(leaseSeconds = 0)) {
      escalated.countDown()
      error("database is locked")
    }

    try {
      assertTrue(escalated.await(TICK_TIMEOUT_SECONDS, TimeUnit.SECONDS), "the first tick never ran")
      awaitUntil { diagnostics.errors.isNotEmpty() }
    } finally {
      heartbeat.stop()
    }
    assertTrue(
      diagnostics.errors.first().contains("lease has already expired"),
      "a failure window past the lease must escalate: ${diagnostics.errors.first()}",
    )
  }

  @Test
  fun `lost fencing stops renewal and is reported to the run owner`() {
    val diagnostics = RecordingDiagnostics()
    val supervisor = JdkFeatureTaskRuntimeWorkerSupervisor(diagnostics)
    val ticks = AtomicInteger()
    val observed = CountDownLatch(1)

    val heartbeat = supervisor.startHeartbeat(plan()) {
      ticks.incrementAndGet()
      observed.countDown()
      FeatureTaskRuntimeHeartbeatTick.FencingLost("another owner holds the lease")
    }

    try {
      assertTrue(observed.await(TICK_TIMEOUT_SECONDS, TimeUnit.SECONDS), "the first tick never ran")
      awaitUntil { diagnostics.errors.isNotEmpty() }
      Thread.sleep(QUIESCE_MILLIS)
      assertEquals(1, ticks.get(), "renewal must stop after fencing is lost")
    } finally {
      heartbeat.stop()
    }
    assertEquals("another owner holds the lease", heartbeat.fencingLostReason())
    assertTrue(
      assertSingle(diagnostics.errors).contains("another owner holds the lease"),
      "the run owner must be told why renewal stopped",
    )
  }

  private fun plan(leaseSeconds: Long = 30) = FeatureTaskRuntimeHeartbeatPlan(
    label = "wftr-label",
    intervalSeconds = 1,
    leaseSeconds = leaseSeconds,
    retryDelaySeconds = 1,
  )

  private fun <T> assertSingle(values: List<T>): T {
    assertEquals(1, values.size, "expected exactly one entry, got $values")
    return values.first()
  }

  private fun awaitUntil(condition: () -> Boolean) {
    val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(TICK_TIMEOUT_SECONDS)
    while (!condition() && System.nanoTime() < deadlineNanos) {
      Thread.sleep(POLL_MILLIS)
    }
    assertTrue(condition(), "condition never became true within ${TICK_TIMEOUT_SECONDS}s")
  }

  private companion object {
    const val TICK_TIMEOUT_SECONDS = 20L
    const val QUIESCE_MILLIS = 2_500L
    const val POLL_MILLIS = 25L
  }
}

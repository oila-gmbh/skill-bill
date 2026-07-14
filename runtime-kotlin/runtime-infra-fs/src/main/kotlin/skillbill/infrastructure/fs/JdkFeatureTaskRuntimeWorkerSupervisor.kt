package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Inject
class JdkFeatureTaskRuntimeWorkerSupervisor : FeatureTaskRuntimeWorkerSupervisor {
  override fun currentProcess(): FeatureTaskRuntimeProcessIdentity {
    val handle = ProcessHandle.current()
    return FeatureTaskRuntimeProcessIdentity(
      hostIdentity = InetAddress.getLocalHost().hostName,
      bootIdentity = bootIdentity(),
      pid = handle.pid(),
      processBirthToken = birthToken(handle)
        ?: error("The current process does not expose process-birth evidence."),
    )
  }

  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection =
    runCatching { currentProcess() }.fold(
      onSuccess = { local -> inspectLocalOwnership(ownership, local) },
      onFailure = {
        FeatureTaskRuntimeProcessInspection.Unsupported(it.message ?: "Local process identity is unavailable.")
      },
    )

  private fun inspectLocalOwnership(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    local: FeatureTaskRuntimeProcessIdentity,
  ): FeatureTaskRuntimeProcessInspection {
    if (ownership.hostIdentity != local.hostIdentity || ownership.bootIdentity != local.bootIdentity) {
      return FeatureTaskRuntimeProcessInspection.OwnershipMismatch(
        "Worker ownership belongs to a different host or boot session.",
      )
    }
    val handle = ProcessHandle.of(ownership.pid).orElse(null) ?: return FeatureTaskRuntimeProcessInspection.NotRunning
    val birth = birthToken(handle)
      ?: return FeatureTaskRuntimeProcessInspection.Unsupported("Worker PID has no verifiable birth evidence.")
    return if (handle.isAlive && birth == ownership.processBirthToken) {
      FeatureTaskRuntimeProcessInspection.ExactLive
    } else if (!handle.isAlive) {
      FeatureTaskRuntimeProcessInspection.NotRunning
    } else {
      FeatureTaskRuntimeProcessInspection.OwnershipMismatch("Worker PID was reused by a different process.")
    }
  }

  override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean =
    exactHandle(ownership)?.destroy() ?: false

  override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean =
    exactHandle(ownership)?.destroyForcibly() ?: false

  override fun startHeartbeat(intervalSeconds: Long, heartbeat: () -> Unit): FeatureTaskRuntimeHeartbeat {
    val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
      Thread(runnable, "skill-bill-worker-heartbeat").apply { isDaemon = true }
    }
    executor.scheduleAtFixedRate({ heartbeat() }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS)
    return FeatureTaskRuntimeHeartbeat(executor::shutdownNow)
  }

  override fun pause(durationMillis: Long) {
    Thread.sleep(durationMillis)
  }

  private fun exactHandle(ownership: FeatureTaskRuntimeWorkerOwnership): ProcessHandle? =
    if (inspect(ownership) == FeatureTaskRuntimeProcessInspection.ExactLive) {
      ProcessHandle.of(ownership.pid).orElse(null)
    } else {
      null
    }

  private fun birthToken(handle: ProcessHandle): String? =
    handle.info().startInstant().orElse(null)?.toEpochMilli()?.toString()

  private fun bootIdentity(): String {
    val linuxBootId = Path.of("/proc/sys/kernel/random/boot_id")
    if (Files.isReadable(linuxBootId)) return Files.readString(linuxBootId).trim()
    val uptime = ProcessHandle.current().info().startInstant().orElseThrow()
      .minus(Duration.ofMillis(ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO).toMillis()))
    return "fallback-${uptime.epochSecond}"
  }
}

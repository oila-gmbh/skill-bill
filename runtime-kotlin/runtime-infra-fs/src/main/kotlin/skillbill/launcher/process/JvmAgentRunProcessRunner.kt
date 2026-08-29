package skillbill.launcher.process

import me.tatarka.inject.annotations.Inject
import skillbill.ports.agentrun.model.AgentRunLivenessSnapshot
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.review.GovernedReviewEvidenceEndpointHandle
import skillbill.workflow.goal.model.GoalProgressOutcome
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Inject
class JvmAgentRunProcessRunner : AgentRunProcessRunner {
  override fun run(request: AgentRunProcessRequest): AgentRunProcessResult {
    request.reviewEvidenceEndpoint?.let(liveEndpoints::add)
    return try {
      runGoverned(request)
    } finally {
      closeEndpoint(request)
    }
  }

  private fun runGoverned(request: AgentRunProcessRequest): AgentRunProcessResult {
    var startedProcess: ProcessStart? = null
    val processStart = runCatching {
      request.spawnAuthorization?.withAuthorization {
        startProcess(request).also { startedProcess = it }
      } ?: startProcess(request).also { startedProcess = it }
    }.getOrElse { failure ->
      cleanupProcessStart(startedProcess)
      throw failure
    }
    return when (processStart) {
      is ProcessStart.Failed -> spawnFailure(processStart.error)
      is ProcessStart.Started -> runStartedProcess(
        process = processStart.process,
        stdoutStream = processStart.process.inputStream,
        stderrStream = processStart.process.errorStream,
        request = request,
      )
    }
  }

  companion object {
    private val liveProcesses = ConcurrentHashMap.newKeySet<Process>()
    private val liveEndpoints =
      ConcurrentHashMap.newKeySet<GovernedReviewEvidenceEndpointHandle>()

    init {
      Runtime.getRuntime().addShutdownHook(object : Thread("skill-bill-agent-run-shutdown") {
        override fun run() {
          reapLiveProcesses(liveProcesses.toList())
          liveEndpoints.toList().forEach { endpoint ->
            liveEndpoints.remove(endpoint)
            runCatching { endpoint.close() }
          }
        }
      })
    }

    internal fun closeEndpoint(request: AgentRunProcessRequest) {
      val endpoint = request.reviewEvidenceEndpoint ?: return
      liveEndpoints.remove(endpoint)
      runCatching { endpoint.close() }.onFailure { failure ->
        runCatching {
          request.outputSink.write(
            AgentRunOutputStream.STDERR,
            "governed review evidence endpoint teardown failed: ${failure.message.orEmpty()}\n",
          )
        }
      }
    }

    internal fun reapLiveProcesses(processes: List<Process>) {
      processes.forEach { process -> runCatching { process.destroy() } }
      processes.forEach { process ->
        runCatching { process.waitFor(DESTROY_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
        if (process.isAlive) runCatching { process.destroyForcibly() }
      }
    }
  }

  private fun runStartedProcess(
    process: Process,
    stdoutStream: InputStream,
    stderrStream: InputStream,
    request: AgentRunProcessRequest,
  ): AgentRunProcessResult {
    liveProcesses.add(process)
    val mcpStartupObservedAtStart = request.mcpStartupProbe.safeStartupObserved()
    val outputTracker = OutputObservationTracker()
    val lifecycleEmitter = ProcessLifecycleEmitter(request)
    val stdout = CappedUtf8Drain(
      input = stdoutStream,
      limitBytes = AGENT_RUN_OUTPUT_LIMIT_BYTES,
      outputStream = AgentRunOutputStream.STDOUT,
      outputSink = request.outputSink,
      onChunkRead = { outputTracker.markObserved() },
    ).also { it.start() }
    val stderr = CappedUtf8Drain(
      input = stderrStream,
      limitBytes = AGENT_RUN_OUTPUT_LIMIT_BYTES,
      outputStream = AgentRunOutputStream.STDERR,
      outputSink = request.outputSink,
      onChunkRead = { outputTracker.markObserved() },
    ).also { it.start() }
    writeAndCloseStdin(process, request.stdinText)
    lifecycleEmitter.emitStarted(process.isAlive)
    val wait = try {
      Result.success(waitForProcess(process, request, outputTracker, lifecycleEmitter))
    } catch (interrupt: InterruptedException) {
      Result.failure(interrupt)
    }
    return finishRun(
      process,
      request,
      wait,
      outputTracker,
      stdout,
      stderr,
      lifecycleEmitter,
      mcpStartupObservedAtStart,
    )
  }

  @Suppress("LongParameterList")
  private fun finishRun(
    process: Process,
    request: AgentRunProcessRequest,
    waitResult: Result<ProcessWait>,
    outputTracker: OutputObservationTracker,
    stdout: CappedUtf8Drain,
    stderr: CappedUtf8Drain,
    lifecycleEmitter: ProcessLifecycleEmitter,
    mcpStartupObservedAtStart: Boolean,
  ): AgentRunProcessResult {
    var interrupted = waitResult.exceptionOrNull() is InterruptedException
    val wait = waitResult.getOrNull()
    val finished = wait?.finished == true
    if (!finished) {
      process.destroyForcibly()
      runCatching { process.waitFor(DESTROY_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
        .onFailure { error -> if (error is InterruptedException) interrupted = true }
    }
    liveProcesses.remove(process)
    stdout.join()
    stderr.join()
    val terminalOutcome = when {
      interrupted -> GoalProgressOutcome.CANCELLED
      finished -> GoalProgressOutcome.SUCCEEDED
      else -> GoalProgressOutcome.TIMED_OUT
    }
    lifecycleEmitter.emitCompleted(processAlive = false, outcome = terminalOutcome)
    if (interrupted) {
      Thread.currentThread().interrupt()
      return interruptedResult(
        stdout,
        stderr,
        outputTracker,
        mcpStartupObservedAtStart || request.mcpStartupProbe.safeStartupObserved(),
      )
    }
    return AgentRunProcessResult(
      exitStatus = if (finished) process.exitValue() else null,
      stdout = stdout.text(),
      stdoutBytes = stdout.bytes(),
      stderr = stderr.text().withTimeoutMessage(requireNotNull(wait), request),
      timedOut = !finished,
      interrupted = false,
      spawnFailed = false,
      liveness = wait.liveness,
      processStarted = true,
      mcpStartupObserved = mcpStartupObservedAtStart || request.mcpStartupProbe.safeStartupObserved(),
      stdoutTruncated = stdout.wasTruncated(),
      stdoutByteSize = stdout.totalByteSize(),
      stdoutSha256 = stdout.sha256(),
    )
  }

  private fun interruptedResult(
    stdout: CappedUtf8Drain,
    stderr: CappedUtf8Drain,
    outputTracker: OutputObservationTracker,
    mcpStartupObserved: Boolean,
  ): AgentRunProcessResult {
    val interruptMessage = "Agent run interrupted by parent signal before completion."
    return AgentRunProcessResult(
      exitStatus = null,
      stdout = stdout.text(),
      stdoutBytes = stdout.bytes(),
      stderr = stderr.text().let { existing ->
        if (existing.isBlank()) {
          interruptMessage
        } else {
          "$existing\n$interruptMessage"
        }
      },
      timedOut = false,
      interrupted = true,
      spawnFailed = false,
      processStarted = true,
      mcpStartupObserved = mcpStartupObserved,
      liveness = AgentRunLivenessSnapshot(
        phase = "watchdog",
        reason = "parent_interrupted",
        processState = "killed",
        lastOutputAt = outputTracker.lastObservedAt()?.toIsoUtc(),
      ),
      stdoutTruncated = stdout.wasTruncated(),
      stdoutByteSize = stdout.totalByteSize(),
      stdoutSha256 = stdout.sha256(),
    )
  }

  private fun waitForProcess(
    process: Process,
    request: AgentRunProcessRequest,
    outputTracker: OutputObservationTracker,
    lifecycleEmitter: ProcessLifecycleEmitter,
  ): ProcessWait = ProcessWaitLoop(process, request, outputTracker, lifecycleEmitter).wait()

  private fun spawnFailure(error: Exception): AgentRunProcessResult = AgentRunProcessResult(
    exitStatus = null,
    stdout = "",
    stderr = error.message.orEmpty(),
    timedOut = false,
    interrupted = false,
    spawnFailed = true,
  )

  private fun startProcess(request: AgentRunProcessRequest): ProcessStart = try {
    ProcessStart.Started(buildProcess(request).start())
  } catch (error: IOException) {
    ProcessStart.Failed(error)
  } catch (error: SecurityException) {
    ProcessStart.Failed(error)
  }

  private fun buildProcess(request: AgentRunProcessRequest): ProcessBuilder = ProcessBuilder(request.command)
    .directory(request.workingDirectory.toFile())
    .also { configureLaunchEnvironment(it, request) }

  private fun cleanupProcessStart(start: ProcessStart?) {
    when (start) {
      is ProcessStart.Started -> reapLiveProcesses(listOf(start.process))
      is ProcessStart.Failed, null -> Unit
    }
  }
}

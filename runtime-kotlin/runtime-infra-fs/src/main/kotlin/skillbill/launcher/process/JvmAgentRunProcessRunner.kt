@file:Suppress("TooManyFunctions")

package skillbill.launcher.process

import me.tatarka.inject.annotations.Inject
import skillbill.goalrunner.model.GoalRunnerLivenessClassifier
import skillbill.goalrunner.model.GoalRunnerLivenessDecision
import skillbill.goalrunner.model.GoalRunnerLivenessInputs
import skillbill.goalrunner.model.GoalRunnerLivenessState
import skillbill.ports.agentrun.model.AgentRunDeclaredProgressSnapshot
import skillbill.ports.agentrun.model.AgentRunLivenessSnapshot
import skillbill.ports.agentrun.model.AgentRunMcpStartupProbe
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.AgentRunProgressEmission
import skillbill.ports.review.GovernedReviewEvidenceEndpointHandle
import skillbill.workflow.model.GoalProgressEvent
import skillbill.workflow.model.GoalProgressEventKind
import skillbill.workflow.model.GoalProgressOutcome
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.time.DurationUnit

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
    private val liveProcesses = java.util.concurrent.ConcurrentHashMap.newKeySet<Process>()
    private val liveEndpoints =
      java.util.concurrent.ConcurrentHashMap.newKeySet<GovernedReviewEvidenceEndpointHandle>()

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

private fun writeAndCloseStdin(process: Process, stdinText: String?) {
  runCatching {
    process.outputStream.use { output ->
      if (stdinText != null) {
        output.write(stdinText.toByteArray(StandardCharsets.UTF_8))
      }
    }
  }
}

private data class ProcessWait(
  val finished: Boolean,
  val progressIdleTimedOut: Boolean,
  val fileActivityGraceExhausted: Boolean,
  val wallClockTimedOut: Boolean,
  val liveness: AgentRunLivenessSnapshot? = null,
)

@Suppress("TooManyFunctions")
private class ProcessWaitLoop(
  private val process: Process,
  private val request: AgentRunProcessRequest,
  private val outputTracker: OutputObservationTracker,
  private val lifecycleEmitter: ProcessLifecycleEmitter,
) {
  private val timeoutMillis = request.timeout
    ?.toLong(DurationUnit.MILLISECONDS)
    ?.coerceAtLeast(MIN_TIMEOUT_MILLIS)
  private val idleTimeoutNanos = request.progressIdleTimeout
    ?.toLong(DurationUnit.NANOSECONDS)
    ?.coerceAtLeast(MIN_TIMEOUT_NANOS)
  private val fileActivityGraceNanos = request.fileActivityGraceTimeout
    .toLong(DurationUnit.NANOSECONDS)
    .coerceAtLeast(MIN_TIMEOUT_NANOS)
  private val statusHeartbeatNanos = request.statusHeartbeatInterval
    .toLong(DurationUnit.NANOSECONDS)
    .coerceAtLeast(MIN_TIMEOUT_NANOS)
  private val operationDeadlineNanos = request.operationDeadline
    ?.toLong(DurationUnit.NANOSECONDS)
    ?.coerceAtLeast(MIN_TIMEOUT_NANOS)
  private val startNanos = System.nanoTime()
  private var lastWorkflowProgressNanos = startNanos
  private var lastStatusHeartbeatNanos = startNanos
  private var lastLiveHeartbeatNanos = startNanos
  private var lastProgressToken = request.progressProbe.safeProgressToken()
  private var lastActivityToken = request.activityProbe.safeActivityToken()
  private var fileActivityWindowStartNanos: Long? = null
  private var lastProgressLabel: String? = request.progressProbe.safeProgressLabel()
  private var lastProgressInstant: Instant? = null
  private var lastSnapshotInstant: Instant? = null
  private var lastActivityLabel: String? = null
  private var lastActivityInstant: Instant? = null
  private var lastObservedOutputMillis: Long? = null
  private var lastOutputNanos: Long? = null

  // SKILL-64 Subtask 3 (AC20-AC24): authoritative declared-progress tracking.
  private var declaredTracker = DeclaredProgressTracker(startNanos)

  fun wait(): ProcessWait {
    var wait: ProcessWait? = null
    while (wait == null) {
      wait = nextWait()
    }
    return wait
  }

  private fun nextWait(): ProcessWait? {
    if (request.reviewEvidenceBroker?.terminalOutcome() != null) {
      return ProcessWait(
        finished = false,
        progressIdleTimedOut = false,
        fileActivityGraceExhausted = false,
        wallClockTimedOut = false,
        liveness = declaredLiveness("review_budget", "review_context_budget_exceeded", "killed", killLivenessState()),
      )
    }
    val waitMillis = waitMillisBeforeNextPoll() ?: return ProcessWait(
      finished = false,
      progressIdleTimedOut = false,
      fileActivityGraceExhausted = false,
      wallClockTimedOut = true,
      liveness = declaredLiveness("watchdog", "wall_clock_timeout", "killed", killLivenessState()),
    )
    return when {
      process.waitFor(waitMillis, TimeUnit.MILLISECONDS) ->
        ProcessWait(
          finished = true,
          progressIdleTimedOut = false,
          fileActivityGraceExhausted = false,
          wallClockTimedOut = false,
          liveness = liveness("watchdog", "process_exited", "exited"),
        )
      else -> pollProgress()
    }
  }

  private fun waitMillisBeforeNextPoll(): Long? {
    val configuredTimeoutMillis = timeoutMillis ?: return PROGRESS_POLL_INTERVAL_MILLIS
    val remainingMillis = configuredTimeoutMillis - elapsedMillis()
    return if (remainingMillis <= 0) {
      null
    } else {
      min(PROGRESS_POLL_INTERVAL_MILLIS, remainingMillis)
    }
  }

  private fun pollProgress(): ProcessWait? {
    val nowNanos = System.nanoTime()
    pollDeclaredProgress(nowNanos)
    pollWorkflowProgress(nowNanos)
    pollFileActivity(nowNanos)
    pollOutputActivity(nowNanos)
    pollStatusHeartbeat(nowNanos)
    // SKILL-64 Subtask 3 (AC20-AC23): when the worker has declared a progress
    // event, the deterministic taxonomy is authoritative. mtime/stdout/token
    // signals below stay as non-authoritative hints only.
    return if (declaredTracker.hasDeclaredEvent) {
      declaredProgressWait(nowNanos)
    } else {
      legacyIdleWait(nowNanos)
    }
  }

  private fun pollDeclaredProgress(nowNanos: Long) {
    val snapshot = request.declaredProgressProbe.safeDeclaredProgress() ?: return
    declaredTracker.observe(snapshot, nowNanos)
  }

  private fun declaredProgressWait(nowNanos: Long): ProcessWait? {
    val decision = declaredTracker.classify(nowNanos, operationDeadlineNanos, idleTimeoutNanos)
    return when (decision.state) {
      GoalRunnerLivenessState.UNRESPONSIVE -> ProcessWait(
        finished = false,
        progressIdleTimedOut = true,
        fileActivityGraceExhausted = false,
        wallClockTimedOut = false,
        liveness = declaredLiveness("watchdog", "operation_deadline_overrun", "killed", decision.state),
      )
      // working/progressing disarm the idle timeout; idle arms it but the
      // configured idle window is still honoured before any kill.
      GoalRunnerLivenessState.IDLE ->
        if (idleTimeoutNanos != null && nowNanos - declaredTracker.lastAdvanceNanos >= idleTimeoutNanos) {
          val processLiveWithinWindow = request.idlePolicy.extendIdleWindow(idleSignals(idleTimeoutNanos, nowNanos))
          if (processLiveWithinWindow) {
            null
          } else {
            ProcessWait(
              finished = false,
              progressIdleTimedOut = true,
              fileActivityGraceExhausted = false,
              wallClockTimedOut = false,
              liveness = declaredLiveness("watchdog", "progress_idle_timeout", "killed", decision.state),
            )
          }
        } else {
          null
        }
      GoalRunnerLivenessState.WORKING, GoalRunnerLivenessState.PROGRESSING -> null
    }
  }

  private fun legacyIdleWait(nowNanos: Long): ProcessWait? =
    if (idleTimeoutNanos != null && nowNanos - lastWorkflowProgressNanos >= idleTimeoutNanos) {
      val graceActive = fileActivityWindowStartNanos?.let { windowStart ->
        nowNanos - windowStart < fileActivityGraceNanos
      } == true
      val processLiveWithinWindow = request.idlePolicy.extendIdleWindow(idleSignals(idleTimeoutNanos, nowNanos))
      if (graceActive || processLiveWithinWindow) {
        null
      } else {
        ProcessWait(
          finished = false,
          progressIdleTimedOut = true,
          fileActivityGraceExhausted = fileActivityWindowStartNanos != null,
          wallClockTimedOut = false,
          liveness = declaredLiveness("watchdog", "progress_idle_timeout", "killed", GoalRunnerLivenessState.IDLE),
        )
      }
    } else {
      null
    }

  private fun idleSignals(idleTimeoutNanos: Long, nowNanos: Long): AgentRunIdleSignals = AgentRunIdleSignals(
    lastLiveHeartbeatNanos = lastLiveHeartbeatNanos,
    lastOutputNanos = lastOutputNanos,
    idleTimeoutNanos = idleTimeoutNanos,
    nowNanos = nowNanos,
  )

  private fun pollWorkflowProgress(nowNanos: Long) {
    val progressToken = request.progressProbe.safeProgressToken()
    if (progressToken != lastProgressToken) {
      lastProgressToken = progressToken
      lastWorkflowProgressNanos = nowNanos
      lastProgressInstant = Instant.now()
      fileActivityWindowStartNanos = null
      writeProgressLabel()
    }
  }

  // The output drains stamp wall-clock millis from their own threads; translate each new
  // observation onto this loop's monotonic clock so idle arithmetic stays monotonic.
  private fun pollOutputActivity(nowNanos: Long) {
    val observedMillis = outputTracker.lastObservedAt()?.toEpochMilli() ?: return
    if (observedMillis != lastObservedOutputMillis) {
      lastObservedOutputMillis = observedMillis
      lastOutputNanos = nowNanos
    }
  }

  private fun pollFileActivity(nowNanos: Long) {
    val activityToken = request.activityProbe.safeActivityToken()
    if (activityToken != lastActivityToken) {
      lastActivityToken = activityToken
      lastActivityInstant = Instant.now()
      if (fileActivityWindowStartNanos == null) {
        fileActivityWindowStartNanos = nowNanos
      }
      writeActivityLabel()
    }
  }

  private fun pollStatusHeartbeat(nowNanos: Long) {
    val alive = process.isAlive
    // Track in-memory on every poll so HEARTBEAT_EXTENDED can extend the idle window at any poll
    // frequency — SQLite contention can silently null out DB-backed signals; process.isAlive never can.
    if (alive) lastLiveHeartbeatNanos = nowNanos
    if (nowNanos - lastStatusHeartbeatNanos < statusHeartbeatNanos) return
    lastStatusHeartbeatNanos = nowNanos
    lifecycleEmitter.emitHeartbeat(alive)
    request.progressProbe.safeProgressLabel()?.takeIf(String::isNotBlank)?.let { label ->
      lastProgressLabel = label
    }
    val workflowLabel = lastProgressLabel?.takeIf(String::isNotBlank)
    val activityLabel = lastActivityLabel?.takeIf(String::isNotBlank)
    val details = listOfNotNull(
      workflowLabel?.let { "workflow: $it" },
      activityLabel?.let { "file activity: $it" },
    ).joinToString("; ")
      .takeIf(String::isNotBlank)
      ?.let { "; $it" }
      .orEmpty()
    request.outputSink.write(
      AgentRunOutputStream.STDERR,
      "skill-bill: status heartbeat (${request.statusHeartbeatInterval}): child run still active$details\n",
    )
  }

  private fun writeProgressLabel() {
    request.progressProbe.safeProgressLabel()
      ?.takeIf(String::isNotBlank)
      ?.let { label ->
        lastProgressLabel = label
        lastSnapshotInstant = Instant.now()
        request.outputSink.write(AgentRunOutputStream.STDERR, "skill-bill: workflow progress: $label\n")
      }
  }

  private fun writeActivityLabel() {
    val activityLabel = request.activityProbe.safeActivityLabel()?.takeIf(String::isNotBlank)
    if (activityLabel != null) {
      lastActivityLabel = activityLabel
    }
    val workflowLabel = request.progressProbe.safeProgressLabel()?.takeIf(String::isNotBlank)
    val suffix = listOfNotNull(activityLabel, workflowLabel).joinToString("; ")
      .takeIf(String::isNotBlank)
      ?.let { label -> ": $label" }
      .orEmpty()
    request.outputSink.write(
      AgentRunOutputStream.STDERR,
      "skill-bill: file activity observed; durable workflow progress is still pending$suffix\n",
    )
  }

  private fun liveness(phase: String, reason: String, processState: String): AgentRunLivenessSnapshot =
    declaredLiveness(phase, reason, processState, livenessState = null)

  private fun killLivenessState(): GoalRunnerLivenessState {
    if (declaredTracker.activeOperationName != null) return GoalRunnerLivenessState.WORKING
    val timeout = idleTimeoutNanos ?: return GoalRunnerLivenessState.IDLE
    val lastOutput = lastOutputNanos ?: return GoalRunnerLivenessState.IDLE
    return if (System.nanoTime() - lastOutput < timeout) {
      GoalRunnerLivenessState.PROGRESSING
    } else {
      GoalRunnerLivenessState.IDLE
    }
  }

  // SKILL-64 Subtask 3 (AC24): report the authoritative durable step from the
  // typed declared event when present, never a regex-parsed local label.
  private fun declaredLiveness(
    phase: String,
    reason: String,
    processState: String,
    livenessState: GoalRunnerLivenessState?,
  ): AgentRunLivenessSnapshot {
    val declared = declaredTracker.latestEvent
    val (parsedWorkflowId, parsedWorkflowStep) = parseWorkflowIdAndStep(lastProgressLabel)
    return AgentRunLivenessSnapshot(
      phase = phase,
      reason = reason,
      processState = processState,
      workflowId = declared?.workflowId ?: parsedWorkflowId,
      workflowStep = declared?.let { it.stepId ?: it.workflowPhase } ?: parsedWorkflowStep,
      lastDurableProgressAt = declared?.timestamp ?: lastProgressInstant?.toIsoUtc(),
      lastDurableProgressLabel = lastProgressLabel?.takeIf(String::isNotBlank),
      lastWorkflowSnapshotAt = lastSnapshotInstant?.toIsoUtc(),
      lastFileActivityAt = lastActivityInstant?.toIsoUtc(),
      lastFileActivityLabel = lastActivityLabel?.takeIf(String::isNotBlank),
      lastOutputAt = outputTracker.lastObservedAt()?.toIsoUtc(),
      livenessState = livenessState,
      activeOperationName = declaredTracker.activeOperationName,
      activeOperationKind = declaredTracker.activeOperationKind,
      activeOperationExpectedLong = declaredTracker.activeOperationExpectedLong,
    )
  }

  private fun elapsedMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
}

/**
 * SKILL-64 Subtask 3 (AC20-AC23): tracks the latest declared progress event and
 * derives the deterministic liveness taxonomy via the pure domain classifier.
 * mtime/stdout/token movement are not consulted here.
 */
private class DeclaredProgressTracker(startNanos: Long) {
  var latestEvent: GoalProgressEvent? = null
    private set
  private var processAlive: Boolean = true
  private var operationActive: Boolean = false
  var activeOperationName: String? = null
    private set
  var activeOperationKind: String? = null
    private set
  var activeOperationExpectedLong: Boolean = false
    private set
  private var operationStartedNanos: Long = startNanos
  var lastAdvanceNanos: Long = startNanos
    private set
  private var lastSequenceNumber: Int = Int.MIN_VALUE

  val hasDeclaredEvent: Boolean get() = latestEvent != null

  fun observe(snapshot: AgentRunDeclaredProgressSnapshot, nowNanos: Long) {
    val event = snapshot.latestEvent
    processAlive = snapshot.processAlive
    if (event.sequenceNumber <= lastSequenceNumber && latestEvent != null) {
      // Stale or duplicate event: refresh only the process-alive hint.
      return
    }
    lastSequenceNumber = event.sequenceNumber
    latestEvent = event
    lastAdvanceNanos = nowNanos
    when (event.eventKind) {
      GoalProgressEventKind.OPERATION_STARTED, GoalProgressEventKind.OPERATION_HEARTBEAT -> {
        // SKILL-64 Subtask 3 (F-P01): seed the operation start from the FIRST
        // operation event of a previously-inactive operation, HEARTBEAT
        // included. The durable store keeps only the latest declared event, so
        // the supervisor frequently first ingests a HEARTBEAT; measuring the
        // operation deadline from process start would falsely kill a
        // legitimately long operation (AC22). When the operation name changes we
        // also treat it as a fresh operation start.
        val wasActive = operationActive
        val sameOperation = activeOperationName == event.operationName
        operationActive = true
        activeOperationName = event.operationName
        activeOperationKind = event.operationKind
        activeOperationExpectedLong = event.expectedLong
        if (!wasActive || !sameOperation || event.eventKind == GoalProgressEventKind.OPERATION_STARTED) {
          operationStartedNanos = nowNanos
        }
      }
      GoalProgressEventKind.OPERATION_COMPLETED -> {
        operationActive = false
        activeOperationName = null
        activeOperationKind = null
        activeOperationExpectedLong = false
      }
      GoalProgressEventKind.PHASE_STARTED, GoalProgressEventKind.PHASE_COMPLETED -> Unit
    }
  }

  fun classify(nowNanos: Long, operationDeadlineNanos: Long?, idleTimeoutNanos: Long?): GoalRunnerLivenessDecision {
    val deadlineOverrun = operationDeadlineNanos != null &&
      operationActive &&
      (nowNanos - operationStartedNanos) >= operationDeadlineNanos
    val durableAdvanceWithinInterval = idleTimeoutNanos?.let { window ->
      nowNanos - lastAdvanceNanos < window
    } ?: true
    return GoalRunnerLivenessClassifier.classify(
      GoalRunnerLivenessInputs(
        processAlive = processAlive,
        operationActive = operationActive,
        operationExpectedLong = activeOperationExpectedLong,
        durableAdvanceWithinInterval = durableAdvanceWithinInterval,
        operationDeadlineOverrun = deadlineOverrun,
        wallClockCapExceeded = false,
      ),
    )
  }
}

/**
 * SKILL-64 Subtask 3 (AC25, AC21): drives the declared operation_* lifecycle
 * from the process-lifecycle wrapper. Emits a stable [CHILD_OPERATION_NAME] /
 * [CHILD_OPERATION_KIND] with expected_long=true so a long child run (such as a
 * `gradlew check` phase) is declared automatically, without the phase agent
 * having to self-report. The emitter is effect-free at the type level; the
 * adapter mints timestamp/sequence, resolves the workflow id, and persists best
 * effort. operation_started is emitted at most once.
 */
private class ProcessLifecycleEmitter(private val request: AgentRunProcessRequest) {
  private var started = false
  private var completed = false

  fun emitStarted(processAlive: Boolean) {
    if (started) {
      return
    }
    started = true
    emit(GoalProgressEventKind.OPERATION_STARTED, processAlive, GoalProgressOutcome.NONE)
  }

  fun emitHeartbeat(processAlive: Boolean) {
    if (!started || completed) {
      return
    }
    emit(GoalProgressEventKind.OPERATION_HEARTBEAT, processAlive, GoalProgressOutcome.NONE)
  }

  fun emitCompleted(processAlive: Boolean, outcome: GoalProgressOutcome) {
    if (completed || !started) {
      return
    }
    completed = true
    emit(GoalProgressEventKind.OPERATION_COMPLETED, processAlive, outcome)
  }

  private fun emit(kind: GoalProgressEventKind, processAlive: Boolean, outcome: GoalProgressOutcome) {
    // Best-effort: a faulty emitter must never break the process-wait loop.
    runCatching {
      request.progressEmitter.emit(
        AgentRunProgressEmission(
          eventKind = kind,
          processAlive = processAlive,
          operationName = CHILD_OPERATION_NAME,
          operationKind = CHILD_OPERATION_KIND,
          expectedLong = true,
          outcome = outcome,
          authoritative = false,
        ),
      )
    }
  }

  private companion object {
    const val CHILD_OPERATION_NAME = "child_agent_run"
    const val CHILD_OPERATION_KIND = "long_child_run"
  }
}

private fun skillbill.ports.agentrun.model.AgentRunDeclaredProgressProbe.safeDeclaredProgress():
  AgentRunDeclaredProgressSnapshot? =
  runCatching { latestDeclaredProgress() }.getOrNull()

private fun AgentRunMcpStartupProbe.safeStartupObserved(): Boolean =
  runCatching { startupObserved() }.getOrDefault(false)

private fun skillbill.ports.agentrun.model.AgentRunProgressProbe.safeProgressToken(): String? =
  runCatching { progressToken() }.getOrNull()

private fun skillbill.ports.agentrun.model.AgentRunProgressProbe.safeProgressLabel(): String? =
  runCatching { progressLabel() }.getOrNull()

private fun AgentRunActivityProbe.safeActivityToken(): String? = runCatching { activityToken() }.getOrNull()

private fun AgentRunActivityProbe.safeActivityLabel(): String? = runCatching { activityLabel() }.getOrNull()

private fun String.withTimeoutMessage(wait: ProcessWait, request: AgentRunProcessRequest): String = when {
  wait.progressIdleTimedOut -> withProgressTimeoutMessage(request, wait.fileActivityGraceExhausted, wait.liveness)
  wait.wallClockTimedOut -> withWallClockTimeoutMessage(request, wait.liveness)
  else -> this
}

private fun String.withProgressTimeoutMessage(
  request: AgentRunProcessRequest,
  fileActivityGraceExhausted: Boolean,
  liveness: AgentRunLivenessSnapshot?,
): String {
  val fileActivityDetail = if (fileActivityGraceExhausted) {
    " File activity was observed, but the ${request.fileActivityGraceTimeout} file-activity grace window was exhausted."
  } else {
    " No file activity was observed."
  }
  val livenessDetail = liveness.detailsSuffix()
  val message = "Agent run stopped after ${request.progressIdleTimeout} " +
    "without durable workflow progress.$fileActivityDetail$livenessDetail"
  return if (isBlank()) message else "$this\n$message"
}

private fun String.withWallClockTimeoutMessage(
  request: AgentRunProcessRequest,
  liveness: AgentRunLivenessSnapshot?,
): String {
  val message = "Agent run stopped after optional wall-clock cap ${request.timeout}.${liveness.detailsSuffix()}"
  return if (isBlank()) message else "$this\n$message"
}

private fun AgentRunLivenessSnapshot?.detailsSuffix(): String = this?.let { snapshot ->
  val detail = listOfNotNull(
    snapshot.workflowId?.let { workflowId -> "workflow_id=$workflowId" },
    snapshot.workflowStep?.let { workflowStep -> "step=$workflowStep" },
    snapshot.lastDurableProgressAt?.let { timestamp -> "last_durable_progress_at=$timestamp" },
    snapshot.lastFileActivityAt?.let { timestamp -> "last_file_activity_at=$timestamp" },
    snapshot.lastOutputAt?.let { timestamp -> "last_output_at=$timestamp" },
  ).joinToString(", ")
  if (detail.isBlank()) "" else " Last observations: $detail."
} ?: ""

private sealed interface ProcessStart {
  data class Started(val process: Process) : ProcessStart
  data class Failed(val error: Exception) : ProcessStart
}

private class CappedUtf8Drain(
  private val input: InputStream,
  private val limitBytes: Int?,
  private val outputStream: AgentRunOutputStream,
  private val outputSink: AgentRunOutputSink,
  private val onChunkRead: (String) -> Unit,
) {
  private val output = ByteArrayOutputStream(
    limitBytes?.coerceAtMost(INITIAL_OUTPUT_BUFFER_BYTES) ?: INITIAL_OUTPUT_BUFFER_BYTES,
  )

  @Volatile private var truncated = false
  private var totalByteSize = 0L
  private val digest = java.security.MessageDigest.getInstance("SHA-256")
  private val worker = thread(start = false, isDaemon = true, name = "skillbill-agent-run-output-drain") {
    try {
      input.use { stream ->
        val buffer = ByteArray(DEFAULT_DRAIN_BUFFER_BYTES)
        var remaining = limitBytes
        val decoder = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPLACE)
          .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val carry = ByteBuffer.allocate(DEFAULT_DRAIN_BUFFER_BYTES + UTF8_MAX_BYTES_PER_CODE_POINT)
        val decoded = CharBuffer.allocate(DEFAULT_DRAIN_BUFFER_BYTES)
        while (true) {
          val read = stream.read(buffer)
          if (read == -1) {
            break
          }
          totalByteSize += read
          digest.update(buffer, 0, read)
          // Whether the retention cap still has room at the START of this read: sink forwarding
          // stops once the cap is exhausted, independent of onChunkRead's lifecycle decoding, which
          // must keep observing output regardless of the cap to enforce provider budgets correctly.
          val withinCap = remaining == null || remaining > 0
          carry.put(buffer, 0, read)
          carry.flip()
          decodeAvailable(decoded, withinCap) { decoder.decode(carry, decoded, false) }
          carry.compact()

          val forwarded = remaining?.coerceAtMost(read) ?: read
          if (forwarded > 0) remaining = remaining?.minus(forwarded)
          retain(buffer, read)
        }
        val withinCap = remaining == null || remaining > 0
        carry.flip()
        decodeAvailable(decoded, withinCap) { decoder.decode(carry, decoded, true) }
        decodeAvailable(decoded, withinCap) { decoder.flush(decoded) }
      }
    } catch (_: IOException) {
      // Forced process teardown can close pipes while drain threads are blocked in read().
    }
  }

  /**
   * Retains the TAIL of the stream, not its head. Every structured agent transport puts the only
   * harvestable event last — Claude's and Cursor's terminal `result`, Codex's final `item.text` —
   * so keeping the first bytes discards the answer and keeps the preamble. Growth is bounded by
   * compacting to the last [limitBytes] once the buffer reaches twice that, which costs one copy
   * per cap's worth of output rather than one per read.
   */
  private fun retain(buffer: ByteArray, read: Int) {
    output.write(buffer, 0, read)
    val limit = limitBytes ?: return
    if (totalByteSize > limit) truncated = true
    if (output.size() > limit * 2) compactToTail(limit)
  }

  private fun compactToTail(limit: Int) {
    val retained = output.toByteArray()
    output.reset()
    output.write(retained, retained.size - limit, limit)
  }

  /**
   * Tail retention cuts into whatever line was in flight at the boundary, and every structured
   * decoder here parses line by line. Dropping the leading partial line yields a buffer whose first
   * character starts a real record, so a truncated stream stays parseable instead of failing on a
   * fragment. A cap-sized run with no newline at all has no boundary to find and is returned whole.
   */
  private fun alignToLineStart(bytes: ByteArray): ByteArray {
    val newline = bytes.indexOf('\n'.code.toByte())
    return if (newline < 0) bytes else bytes.copyOfRange(newline + 1, bytes.size)
  }

  private fun decodeAvailable(decoded: CharBuffer, forwardToSink: Boolean, decode: () -> java.nio.charset.CoderResult) {
    while (true) {
      val result = decode()
      decoded.flip()
      if (decoded.hasRemaining()) {
        val chunk = decoded.toString()
        onChunkRead(chunk)
        if (forwardToSink) outputSink.write(outputStream, chunk)
      }
      decoded.clear()
      if (!result.isOverflow) return
    }
  }

  fun start() {
    worker.start()
  }

  fun join() {
    worker.join(DRAIN_JOIN_TIMEOUT_MILLIS)
  }

  fun text(): String = String(bytes(), StandardCharsets.UTF_8)

  fun bytes(): ByteArray {
    val limit = limitBytes ?: return output.toByteArray()
    val retained = output.toByteArray()
    if (retained.size <= limit) return retained
    return alignToLineStart(retained.copyOfRange(retained.size - limit, retained.size))
  }

  /** True once more bytes arrived than the retention cap could keep, so [text] is incomplete. */
  fun wasTruncated(): Boolean = truncated

  fun totalByteSize(): Long = totalByteSize

  fun sha256(): String = digest.digest().joinToString("") { "%02x".format(it) }
}

private class OutputObservationTracker {
  private val lastObservedMillis = AtomicLong(0L)

  fun markObserved() {
    lastObservedMillis.set(System.currentTimeMillis())
  }

  fun lastObservedAt(): Instant? = lastObservedMillis.get()
    .takeIf { millis -> millis > 0L }
    ?.let(Instant::ofEpochMilli)
}

private fun parseWorkflowIdAndStep(label: String?): Pair<String?, String?> {
  val text = label?.takeIf(String::isNotBlank) ?: return null to null
  val workflow = Regex("""workflow\s+([^\s;]+)""").find(text)?.groupValues?.getOrNull(1)
  val step = Regex("""step\s+([^\s;]+)""").find(text)?.groupValues?.getOrNull(1)
  return workflow to step
}

private fun Instant.toIsoUtc(): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME
  .format(atOffset(ZoneOffset.UTC))

private const val DEFAULT_DRAIN_BUFFER_BYTES = 8192
private const val UTF8_MAX_BYTES_PER_CODE_POINT = 4
private const val INITIAL_OUTPUT_BUFFER_BYTES = DEFAULT_DRAIN_BUFFER_BYTES
private const val DRAIN_JOIN_TIMEOUT_MILLIS = 1_000L
private const val MIN_TIMEOUT_MILLIS = 1L
private const val MIN_TIMEOUT_NANOS = 1L
private const val PROGRESS_POLL_INTERVAL_MILLIS = 250L
private const val DESTROY_WAIT_TIMEOUT_MILLIS = 1_000L

internal fun configureLaunchEnvironment(builder: ProcessBuilder, request: AgentRunProcessRequest) {
  if (!request.inheritEnvironment) {
    val isolated = isolatedLaunchEnvironment(
      builder.environment(),
      request.environment,
      request.environmentPassthroughKeys,
    )
    builder.environment().clear()
    builder.environment().putAll(isolated)
  } else {
    builder.environment().putAll(request.environment)
  }
}

internal fun isolatedLaunchEnvironment(
  parentEnvironment: Map<String, String>,
  overrides: Map<String, String>,
  additionalPassthroughKeys: Set<String> = emptySet(),
): Map<String, String> = parentEnvironment.filterKeys {
  it in ISOLATED_LAUNCH_PASSTHROUGH_KEYS || it in additionalPassthroughKeys
} + overrides

// An isolated launch drops the caller's ambient session state, but the spawned agent still has to
// be executable and still has to resolve the user's own installation: without these the worker has
// no PATH to exec from and no home under which its registered native agents live, so every
// delegated review lane fails preflight as if nothing were installed.
private val ISOLATED_LAUNCH_PASSTHROUGH_KEYS: Set<String> = setOf(
  "HOME",
  "PATH",
  "USER",
  "LOGNAME",
  "SHELL",
  "LANG",
  "LC_ALL",
  "TMPDIR",
  "XDG_CONFIG_HOME",
  "XDG_DATA_HOME",
  "XDG_CACHE_HOME",
  "CLAUDE_CONFIG_DIR",
  "CODEX_HOME",
)

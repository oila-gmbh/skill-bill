@file:Suppress("TooManyFunctions")

package skillbill.launcher.process

import com.sun.jna.Library
import com.sun.jna.Native
import me.tatarka.inject.annotations.Inject
import skillbill.goalrunner.model.GoalRunnerLivenessClassifier
import skillbill.goalrunner.model.GoalRunnerLivenessDecision
import skillbill.goalrunner.model.GoalRunnerLivenessInputs
import skillbill.goalrunner.model.GoalRunnerLivenessState
import skillbill.ports.agentrun.model.AgentRunDeclaredProgressSnapshot
import skillbill.ports.agentrun.model.AgentRunLivenessSnapshot
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.AgentRunProgressEmission
import skillbill.workflow.model.GoalProgressEvent
import skillbill.workflow.model.GoalProgressEventKind
import skillbill.workflow.model.GoalProgressOutcome
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
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
    val processStart = startProcess(request)
    return when (processStart) {
      is ProcessStart.Failed -> spawnFailure(processStart.error)
      is ProcessStart.Started -> runStartedProcess(
        process = processStart.process,
        stdoutStream = processStart.process.inputStream,
        stderrStream = processStart.process.errorStream,
        request = request,
        ptyMasterCloseable = null,
      )
      is ProcessStart.PtyStarted -> runStartedProcess(
        process = processStart.process,
        stdoutStream = processStart.ptyMasterStream,
        stderrStream = InputStream.nullInputStream(),
        request = request,
        ptyMasterCloseable = processStart.ptyMasterCloseable,
      )
    }
  }

  private fun runStartedProcess(
    process: Process,
    stdoutStream: InputStream,
    stderrStream: InputStream,
    request: AgentRunProcessRequest,
    ptyMasterCloseable: AutoCloseable?,
  ): AgentRunProcessResult {
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
    ).also { if (stderrStream !== InputStream.nullInputStream()) it.start() }
    writeAndCloseStdin(process, request.stdinText)
    lifecycleEmitter.emitStarted(process.isAlive)
    val wait = try {
      Result.success(waitForProcess(process, request, outputTracker, lifecycleEmitter))
    } catch (interrupt: InterruptedException) {
      Result.failure(interrupt)
    }
    return finishRun(process, request, wait, outputTracker, stdout, stderr, lifecycleEmitter, ptyMasterCloseable)
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
    ptyMasterCloseable: AutoCloseable?,
  ): AgentRunProcessResult {
    var interrupted = waitResult.exceptionOrNull() is InterruptedException
    val wait = waitResult.getOrNull()
    val finished = wait?.finished == true
    if (!finished) {
      process.destroyForcibly()
      runCatching { process.waitFor(DESTROY_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
        .onFailure { error -> if (error is InterruptedException) interrupted = true }
    }
    stdout.join()
    stderr.join()
    runCatching { ptyMasterCloseable?.close() }
      .onFailure { System.err.println("skill-bill: failed to close PTY master fd: ${it.message}") }
    val terminalOutcome = when {
      interrupted -> GoalProgressOutcome.CANCELLED
      finished -> GoalProgressOutcome.SUCCEEDED
      else -> GoalProgressOutcome.TIMED_OUT
    }
    lifecycleEmitter.emitCompleted(processAlive = false, outcome = terminalOutcome)
    if (interrupted) {
      Thread.currentThread().interrupt()
      return interruptedResult(stdout, stderr, outputTracker)
    }
    return AgentRunProcessResult(
      exitStatus = if (finished) process.exitValue() else null,
      stdout = stdout.text(),
      stderr = stderr.text().withTimeoutMessage(requireNotNull(wait), request),
      timedOut = !finished,
      interrupted = false,
      spawnFailed = false,
      liveness = wait.liveness,
    )
  }

  private fun interruptedResult(
    stdout: CappedUtf8Drain,
    stderr: CappedUtf8Drain,
    outputTracker: OutputObservationTracker,
  ): AgentRunProcessResult {
    val interruptMessage = "Agent run interrupted by parent signal before completion."
    return AgentRunProcessResult(
      exitStatus = null,
      stdout = stdout.text(),
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
      liveness = AgentRunLivenessSnapshot(
        phase = "watchdog",
        reason = "parent_interrupted",
        processState = "killed",
        lastOutputAt = outputTracker.lastObservedAt()?.toIsoUtc(),
      ),
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
    if (request.usePtyStdio) {
      startPtyProcess(request)
    } else {
      ProcessStart.Started(buildProcess(request).start())
    }
  } catch (error: IOException) {
    ProcessStart.Failed(error)
  } catch (error: SecurityException) {
    ProcessStart.Failed(error)
  } catch (error: IllegalStateException) {
    ProcessStart.Failed(IOException("PTY spawn failed: ${error.message}", error))
  }

  private fun buildProcess(request: AgentRunProcessRequest): ProcessBuilder = ProcessBuilder(request.command)
    .directory(request.workingDirectory.toFile())
    .apply {
      if (!request.inheritEnvironment) {
        environment().clear()
      }
      environment().putAll(request.environment)
    }

  private fun startPtyProcess(request: AgentRunProcessRequest): ProcessStart {
    check(System.getProperty("os.name").lowercase().startsWith("linux")) {
      "PTY-backed stdio is only supported on Linux; current platform: ${System.getProperty("os.name")}"
    }
    val (masterFd, slavePath) = openPtyPair()
    val process = try {
      buildProcess(request)
        .redirectInput(java.io.File(slavePath))
        .redirectOutput(java.io.File(slavePath))
        .redirectError(java.io.File(slavePath))
        .start()
    } catch (e: IOException) {
      PosixLib.closeFd(masterFd)
      throw e
    } catch (e: SecurityException) {
      PosixLib.closeFd(masterFd)
      throw e
    }
    val masterStream = PosixLib.masterInputStream(masterFd)
    val masterCloseable = AutoCloseable { PosixLib.closeFd(masterFd) }
    return ProcessStart.PtyStarted(process, masterStream, masterCloseable)
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
    val waitMillis = waitMillisBeforeNextPoll() ?: return ProcessWait(
      finished = false,
      progressIdleTimedOut = false,
      fileActivityGraceExhausted = false,
      wallClockTimedOut = true,
      liveness = liveness("watchdog", "wall_clock_timeout", "killed"),
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
          val processLiveWithinWindow =
            request.idlePolicy.extendIdleWindow(lastLiveHeartbeatNanos, idleTimeoutNanos, nowNanos)
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
      val processLiveWithinWindow =
        request.idlePolicy.extendIdleWindow(lastLiveHeartbeatNanos, idleTimeoutNanos, nowNanos)
      if (graceActive || processLiveWithinWindow) {
        null
      } else {
        ProcessWait(
          finished = false,
          progressIdleTimedOut = true,
          fileActivityGraceExhausted = fileActivityWindowStartNanos != null,
          wallClockTimedOut = false,
          liveness = liveness("watchdog", "progress_idle_timeout", "killed"),
        )
      }
    } else {
      null
    }

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
    if (nowNanos - lastStatusHeartbeatNanos < statusHeartbeatNanos) {
      return
    }
    lastStatusHeartbeatNanos = nowNanos
    val alive = process.isAlive
    lifecycleEmitter.emitHeartbeat(alive)
    // Track in-memory so idle-wait paths can extend the idle window without a DB
    // round-trip. SQLite contention from the child MCP server can silently null
    // out the DB-backed signals; process.isAlive never can.
    if (alive) lastLiveHeartbeatNanos = nowNanos
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
  data class PtyStarted(
    val process: Process,
    val ptyMasterStream: InputStream,
    val ptyMasterCloseable: AutoCloseable,
  ) : ProcessStart
  data class Failed(val error: Exception) : ProcessStart
}

private class CappedUtf8Drain(
  private val input: InputStream,
  private val limitBytes: Int,
  private val outputStream: AgentRunOutputStream,
  private val outputSink: AgentRunOutputSink,
  private val onChunkRead: () -> Unit,
) {
  private val output = ByteArrayOutputStream(limitBytes.coerceAtMost(INITIAL_OUTPUT_BUFFER_BYTES))
  private val worker = thread(start = false, isDaemon = true, name = "skillbill-agent-run-output-drain") {
    try {
      input.use { stream ->
        val buffer = ByteArray(DEFAULT_DRAIN_BUFFER_BYTES)
        var remaining = limitBytes
        while (remaining > 0) {
          val read = stream.read(buffer, 0, remaining.coerceAtMost(buffer.size))
          if (read == -1) {
            break
          }
          onChunkRead()
          output.write(buffer, 0, read)
          outputSink.write(outputStream, String(buffer, 0, read, StandardCharsets.UTF_8))
          remaining -= read
        }
        while (stream.read(buffer) != -1) {
          // Keep draining so the child cannot block on a full pipe after the cap is reached.
        }
      }
    } catch (_: IOException) {
      // Forced process teardown can close pipes while drain threads are blocked in read().
    }
  }

  fun start() {
    worker.start()
  }

  fun join() {
    worker.join(DRAIN_JOIN_TIMEOUT_MILLIS)
  }

  fun text(): String = String(output.toByteArray(), StandardCharsets.UTF_8)
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
private const val INITIAL_OUTPUT_BUFFER_BYTES = DEFAULT_DRAIN_BUFFER_BYTES
private const val DRAIN_JOIN_TIMEOUT_MILLIS = 1_000L
private const val MIN_TIMEOUT_MILLIS = 1L
private const val MIN_TIMEOUT_NANOS = 1L
private const val PROGRESS_POLL_INTERVAL_MILLIS = 250L
private const val DESTROY_WAIT_TIMEOUT_MILLIS = 1_000L

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
private interface PosixCLibrary : Library {
  fun posix_openpt(flags: Int): Int
  fun grantpt(fd: Int): Int
  fun unlockpt(fd: Int): Int
  fun ptsname_r(fd: Int, buf: ByteArray, buflen: Int): Int
  fun read(fd: Int, buf: ByteArray, count: Int): Int
  fun close(fd: Int): Int
}

private object PosixLib {
  private const val O_RDWR = 2
  private const val O_NOCTTY = 0x400
  val lib: PosixCLibrary by lazy {
    Native.load("c", PosixCLibrary::class.java)
  }

  fun openMasterFd(): Int {
    val fd = lib.posix_openpt(O_RDWR or O_NOCTTY)
    check(fd >= 0) { "posix_openpt failed" }
    try {
      check(lib.grantpt(fd) == 0) { "grantpt failed" }
      check(lib.unlockpt(fd) == 0) { "unlockpt failed" }
    } catch (e: IllegalStateException) {
      lib.close(fd)
      throw e
    }
    return fd
  }

  fun slavePath(masterFd: Int): String {
    val buf = ByteArray(PTY_PATH_BUF_SIZE)
    val result = lib.ptsname_r(masterFd, buf, buf.size)
    check(result == 0) { "ptsname_r failed" }
    val nullAt = buf.indexOf(NULL_BYTE)
    return String(buf, 0, if (nullAt >= 0) nullAt else buf.size, StandardCharsets.US_ASCII)
  }

  fun masterInputStream(masterFd: Int): InputStream = PtyMasterInputStream(masterFd)

  fun closeFd(fd: Int) {
    lib.close(fd)
  }
}

private class PtyMasterInputStream(private val fd: Int) : InputStream() {
  @Volatile private var closed = false

  override fun read(): Int {
    if (closed) return -1
    val buf = ByteArray(1)
    val n = PosixLib.lib.read(fd, buf, 1)
    return if (n <= 0) -1 else buf[0].toInt() and BYTE_MASK
  }

  override fun read(buf: ByteArray, off: Int, len: Int): Int {
    if (closed || len == 0) return if (len == 0) 0 else -1
    return if (off == 0) {
      val n = PosixLib.lib.read(fd, buf, len)
      if (n <= 0) -1 else n
    } else {
      val tmp = ByteArray(len)
      val n = PosixLib.lib.read(fd, tmp, len)
      if (n <= 0) return -1
      System.arraycopy(tmp, 0, buf, off, n)
      n
    }
  }

  override fun close() {
    closed = true
  }
}

private fun openPtyPair(): Pair<Int, String> {
  val masterFd = PosixLib.openMasterFd()
  val slavePath = try {
    PosixLib.slavePath(masterFd)
  } catch (e: IllegalStateException) {
    PosixLib.closeFd(masterFd)
    throw e
  }
  return masterFd to slavePath
}

private const val PTY_PATH_BUF_SIZE = 256
private const val NULL_BYTE: Byte = 0
private const val BYTE_MASK = 0xFF

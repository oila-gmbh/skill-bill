package skillbill.launcher.process

import skillbill.goalrunner.model.GoalRunnerLivenessClassifier
import skillbill.goalrunner.model.GoalRunnerLivenessDecision
import skillbill.goalrunner.model.GoalRunnerLivenessInputs
import skillbill.goalrunner.model.GoalRunnerLivenessState
import skillbill.ports.agentrun.model.AgentRunDeclaredProgressProbe
import skillbill.ports.agentrun.model.AgentRunDeclaredProgressSnapshot
import skillbill.ports.agentrun.model.AgentRunLivenessSnapshot
import skillbill.ports.agentrun.model.AgentRunProgressProbe
import skillbill.ports.agentrun.model.AgentRunProgressEmission
import skillbill.workflow.goal.model.GoalProgressEvent
import skillbill.workflow.goal.model.GoalProgressEventKind
import skillbill.workflow.goal.model.GoalProgressOutcome
import skillbill.ports.agentrun.model.AgentRunOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.time.DurationUnit

internal fun writeAndCloseStdin(process: Process, stdinText: String?) {
  runCatching {
    process.outputStream.use { output ->
      if (stdinText != null) {
        output.write(stdinText.toByteArray(StandardCharsets.UTF_8))
      }
    }
  }
}
internal data class ProcessWait(
  val finished: Boolean,
  val progressIdleTimedOut: Boolean,
  val fileActivityGraceExhausted: Boolean,
  val wallClockTimedOut: Boolean,
  val liveness: AgentRunLivenessSnapshot? = null,
)

@Suppress("TooManyFunctions")
internal class ProcessWaitLoop(
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

internal class DeclaredProgressTracker(startNanos: Long) {
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
      return
    }
    lastSequenceNumber = event.sequenceNumber
    latestEvent = event
    lastAdvanceNanos = nowNanos
    when (event.eventKind) {
      GoalProgressEventKind.OPERATION_STARTED, GoalProgressEventKind.OPERATION_HEARTBEAT -> {
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

internal class ProcessLifecycleEmitter(private val request: AgentRunProcessRequest) {
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

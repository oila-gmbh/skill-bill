package skillbill.launcher.process

import skillbill.goalrunner.model.GoalRunnerLivenessState
import java.util.concurrent.TimeUnit
import kotlin.math.min

internal fun ProcessWaitLoop.elapsedMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

internal fun ProcessWaitLoop.waitMillisBeforeNextPoll(): Long? {
  val configuredTimeoutMillis = timeoutMillis ?: return PROGRESS_POLL_INTERVAL_MILLIS
  val remainingMillis = configuredTimeoutMillis - elapsedMillis()
  return if (remainingMillis <= 0) {
    null
  } else {
    min(PROGRESS_POLL_INTERVAL_MILLIS, remainingMillis)
  }
}

internal fun ProcessWaitLoop.pollProgress(): ProcessWait? {
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

internal fun ProcessWaitLoop.pollDeclaredProgress(nowNanos: Long) {
  val snapshot = request.declaredProgressProbe.safeDeclaredProgress() ?: return
  declaredTracker.observe(snapshot, nowNanos)
}

internal fun ProcessWaitLoop.declaredProgressWait(nowNanos: Long): ProcessWait? {
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

internal fun ProcessWaitLoop.legacyIdleWait(nowNanos: Long): ProcessWait? =
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

internal fun ProcessWaitLoop.idleSignals(idleTimeoutNanos: Long, nowNanos: Long): AgentRunIdleSignals =
  AgentRunIdleSignals(
    lastLiveHeartbeatNanos = lastLiveHeartbeatNanos,
    lastOutputNanos = lastOutputNanos,
    idleTimeoutNanos = idleTimeoutNanos,
    nowNanos = nowNanos,
  )

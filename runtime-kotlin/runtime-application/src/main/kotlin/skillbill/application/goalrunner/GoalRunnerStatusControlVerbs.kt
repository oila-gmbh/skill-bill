package skillbill.application.goalrunner

import skillbill.application.goalrunner.model.GoalRunnerPauseResult
import skillbill.application.goalrunner.model.GoalRunnerResumeResult
import skillbill.application.goalrunner.model.GoalRunnerStopStatus
import skillbill.application.goalrunner.model.GoalRunnerStopVerbResult
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_OPERATOR_STOP
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.nio.file.Path
import java.time.Clock

private const val GRACEFUL_TERMINATION_WAIT_MILLIS: Long = 5_000
private const val GRACEFUL_TERMINATION_POLL_MILLIS: Long = 250
private const val GRACEFUL_TERMINATION_POLLS: Int =
  (GRACEFUL_TERMINATION_WAIT_MILLIS / GRACEFUL_TERMINATION_POLL_MILLIS).toInt()

internal class GoalRunnerStatusControlVerbs(
  private val manifestStore: GoalRunnerManifestStore,
  private val clock: Clock,
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
) {
  fun pause(
    issueKey: String,
    dbPathOverride: String?,
    repoRoot: Path = Path.of("").toAbsolutePath().normalize(),
  ): GoalRunnerPauseResult {
    val loaded = manifestStore.loadByIssueKey(issueKey, dbPathOverride, repoRoot)
      ?: return GoalRunnerPauseResult(issueKey = issueKey, status = "not_found")
    val repositoryIdentity = goalRepositoryIdentity(repoRoot)
    manifestStore.bindRepositoryIdentity(loaded.parentWorkflowId, repositoryIdentity, dbPathOverride)
    val control = manifestStore.requestPause(loaded.parentWorkflowId, dbPathOverride)
      ?: return GoalRunnerPauseResult(issueKey = issueKey, status = "not_found")
    val effectiveControl = if (
      control.requiresPauseBoundary(loaded.manifest) && loaded.manifest.isAtUnlaunchedBoundary()
    ) {
      manifestStore.pauseAtBoundary(
        loaded.copy(controlState = control),
        dbPathOverride,
      ).controlState
    } else {
      control
    }
    return GoalRunnerPauseResult(
      issueKey = issueKey,
      parentWorkflowId = loaded.parentWorkflowId,
      status = if (effectiveControl.paused) "paused" else "requested",
      paused = effectiveControl.paused,
      pauseRequested = effectiveControl.pauseRequested,
      pauseReason = effectiveControl.pauseReason,
    )
  }

  fun stop(
    issueKey: String,
    dbPathOverride: String?,
    repoRoot: Path = Path.of("").toAbsolutePath().normalize(),
  ): GoalRunnerStopVerbResult {
    val loaded = manifestStore.loadByIssueKey(issueKey, dbPathOverride, repoRoot)
      ?: return GoalRunnerStopVerbResult(issueKey = issueKey, status = GoalRunnerStopStatus.NOT_FOUND)
    manifestStore.bindRepositoryIdentity(loaded.parentWorkflowId, goalRepositoryIdentity(repoRoot), dbPathOverride)
    val alreadyStopped = loaded.controlState.paused &&
      loaded.controlState.pauseReason == GOAL_PAUSE_REASON_OPERATOR_STOP
    val control = manifestStore.pauseNow(
      parentWorkflowId = loaded.parentWorkflowId,
      reason = GOAL_PAUSE_REASON_OPERATOR_STOP,
      pausedAt = clock.instant().toString(),
      overwriteExistingReason = true,
      dbPathOverride = dbPathOverride,
    ) ?: return GoalRunnerStopVerbResult(issueKey = issueKey, status = GoalRunnerStopStatus.NOT_FOUND)

    fun outcome(status: GoalRunnerStopStatus, terminationAttempted: Boolean = false) = GoalRunnerStopVerbResult(
      issueKey = issueKey,
      status = status,
      parentWorkflowId = loaded.parentWorkflowId,
      pauseReason = control.pauseReason,
      pausedAt = control.pausedAt,
      terminationAttempted = terminationAttempted,
    )

    val lease = manifestStore.executionLease(loaded.parentWorkflowId, dbPathOverride)
    val noLiveLease = if (alreadyStopped) GoalRunnerStopStatus.ALREADY_STOPPED else GoalRunnerStopStatus.NO_LIVE_LEASE
    if (lease == null) return outcome(noLiveLease)
    val ownership = lease.asWorkerOwnership(loaded.parentWorkflowId)
    return runCatching {
      when (workerSupervisor.inspect(ownership)) {
        is FeatureTaskRuntimeProcessInspection.OwnershipMismatch,
        is FeatureTaskRuntimeProcessInspection.Unsupported,
        -> outcome(GoalRunnerStopStatus.IDENTITY_MISMATCH)
        FeatureTaskRuntimeProcessInspection.NotRunning -> {
          manifestStore.releaseExecutionLease(
            parentWorkflowId = loaded.parentWorkflowId,
            ownerToken = lease.ownerToken,
            generation = lease.generation,
            dbPathOverride = dbPathOverride,
          )
          outcome(noLiveLease)
        }
        FeatureTaskRuntimeProcessInspection.ExactLive -> {
          terminateOwner(ownership)
          outcome(GoalRunnerStopStatus.STOPPED, terminationAttempted = true)
        }
      }
    }.getOrElse { outcome(GoalRunnerStopStatus.STOPPED, terminationAttempted = true) }
  }

  fun resume(
    issueKey: String,
    dbPathOverride: String?,
    repoRoot: Path = Path.of("").toAbsolutePath().normalize(),
  ): GoalRunnerResumeResult {
    val loaded = manifestStore.loadByIssueKey(issueKey, dbPathOverride, repoRoot)
      ?: return GoalRunnerResumeResult(issueKey = issueKey, status = "not_found")
    manifestStore.bindRepositoryIdentity(loaded.parentWorkflowId, goalRepositoryIdentity(repoRoot), dbPathOverride)
    val before = manifestStore.controlState(loaded.parentWorkflowId, dbPathOverride)
    if (!before.paused && !before.pauseRequested) {
      return GoalRunnerResumeResult(
        issueKey = issueKey,
        parentWorkflowId = loaded.parentWorkflowId,
        status = "not_paused",
      )
    }
    manifestStore.resume(loaded.parentWorkflowId, dbPathOverride)
      ?: return GoalRunnerResumeResult(issueKey = issueKey, status = "not_found")
    return GoalRunnerResumeResult(
      issueKey = issueKey,
      parentWorkflowId = loaded.parentWorkflowId,
      status = "resumed",
      clearedPauseReason = before.pauseReason,
    )
  }

  private fun terminateOwner(ownership: FeatureTaskRuntimeWorkerOwnership) {
    workerSupervisor.terminateGracefully(ownership)
    repeat(GRACEFUL_TERMINATION_POLLS) {
      if (workerSupervisor.inspect(ownership) != FeatureTaskRuntimeProcessInspection.ExactLive) return
      workerSupervisor.pause(GRACEFUL_TERMINATION_POLL_MILLIS)
    }
    if (workerSupervisor.inspect(ownership) == FeatureTaskRuntimeProcessInspection.ExactLive) {
      workerSupervisor.terminateForcibly(ownership)
    }
  }
}

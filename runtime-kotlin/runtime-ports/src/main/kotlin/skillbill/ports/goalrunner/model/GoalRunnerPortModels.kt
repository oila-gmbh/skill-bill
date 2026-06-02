package skillbill.ports.goalrunner.model

import skillbill.goalrunner.model.GoalAttemptLedgerEntry
import skillbill.goalrunner.model.GoalSessionAccounting
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.GoalProgressEvent
import java.nio.file.Path

data class GoalRunnerManifestState(
  val parentWorkflowId: String,
  val dbPath: String,
  val manifest: DecompositionManifest,
)

data class GoalRunnerSubtaskLaunchRequest(
  val invokedAgentId: String,
  val configuredAgentOverrideId: String?,
  val skillRunRequest: SkillRunRequest,
)

data class GoalRunnerWorkflowProgress(
  val workflowId: String,
  val workflowStatus: String,
  val currentStepId: String,
  val progressToken: String,
  val latestDurableProgressEvent: GoalRunnerProgressEvent? = null,
  val latestGoalObservabilityEvent: GoalObservabilityProgressEvent? = null,
  // SKILL-64 Subtask 3 (AC20-AC23): latest declared progress event surfaced to
  // the supervisor for deterministic liveness; null when none recorded yet.
  val latestDeclaredProgressEvent: GoalProgressEvent? = null,
  val latestLivenessSignal: String? = null,
  val lastSnapshotUpdatedAt: String? = null,
)

data class GoalRunnerObservabilityRecordRequest(
  val workflowId: String,
  val issueKey: String,
  val subtaskId: Int,
  val workflowPhase: String,
  val workerRole: String,
  val livenessClass: String,
  val activitySummary: String,
  val sequenceNumber: Int,
  val timestamp: String,
) {
  init {
    require(workflowId.isNotBlank()) { "workflowId is required." }
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(subtaskId > 0) { "subtaskId must be positive." }
    require(workflowPhase.isNotBlank()) { "workflowPhase is required." }
    require(workerRole.isNotBlank()) { "workerRole is required." }
    require(livenessClass.isNotBlank()) { "livenessClass is required." }
    require(activitySummary.isNotBlank()) { "activitySummary is required." }
    require(sequenceNumber >= 0) { "sequenceNumber must be non-negative." }
    require(timestamp.isNotBlank()) { "timestamp is required." }
  }
}

data class GoalRunnerProgressEvent(
  val stepId: String,
  val attemptCount: Int,
  val kind: String,
  val message: String,
  val sequence: Int,
  val timestamp: String,
)

/**
 * SKILL-64 Subtask 3 (AC21, AC25): durable declared-progress write request.
 * The adapter appends [event] to the bounded goal_progress run history and
 * latest-event artifact keys. The supervisor read seam surfaces the latest
 * declared operation state via [latestDeclaredProgressEvent].
 */
data class GoalRunnerProgressEventRecordRequest(
  val workflowId: String,
  val event: GoalProgressEvent,
) {
  init {
    require(workflowId.isNotBlank()) { "workflowId is required." }
  }
}

/** SKILL-64 Subtask 3 (AC6, AC7): best-effort child-session accounting write request. */
data class GoalRunnerSessionAccountingRecordRequest(
  val workflowId: String,
  val accounting: GoalSessionAccounting,
) {
  init {
    require(workflowId.isNotBlank()) { "workflowId is required." }
  }
}

/** SKILL-64 Subtask 3 (AC10, AC11): append-only attempt/event ledger write request. */
data class GoalRunnerAttemptLedgerRecordRequest(
  val workflowId: String,
  val entry: GoalAttemptLedgerEntry,
) {
  init {
    require(workflowId.isNotBlank()) { "workflowId is required." }
  }
}

/**
 * SKILL-64 Subtask 3 (F-D01): highest persisted sequence numbers across an
 * issue's continuation children for the append-only attempt ledger, the
 * best-effort session accounting stream, and the durable goal_progress stream.
 * `null` means no durable entries exist yet (a fresh run); the recorder then
 * starts from its default base offset. The supervisor-side declared-progress
 * emitter seeds its monotonic goal_progress sequence from [maxProgressSequence]
 * so a resume run stays monotonic instead of restarting at 0.
 */
data class GoalRunnerLedgerSequenceWatermarks(
  val maxLedgerSequence: Int? = null,
  val maxAccountingSequence: Int? = null,
  val maxProgressSequence: Int? = null,
)

data class GoalObservabilityProgressEvent(
  val issueKey: String,
  val subtaskId: Int,
  val workflowPhase: String,
  val workerRole: String,
  val livenessClass: String,
  val activitySummary: String,
  val sequenceNumber: Int,
  val timestamp: String,
)

data class GoalPullRequestRequest(
  val repoRoot: Path,
  val issueKey: String,
  val featureName: String,
  val baseBranch: String,
  val headBranch: String,
  val title: String,
  val body: String,
)

sealed interface GoalPullRequestResult {
  data class Opened(val url: String) : GoalPullRequestResult
  data class Existing(val url: String) : GoalPullRequestResult
  data class Failed(val reason: String) : GoalPullRequestResult
}

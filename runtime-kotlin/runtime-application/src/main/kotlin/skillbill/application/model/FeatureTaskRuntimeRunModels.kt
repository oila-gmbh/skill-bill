package skillbill.application.model

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import java.nio.file.Path
import kotlin.time.Duration

/**
 * The request that drives one deterministic phase-loop run. It carries only inert values; the
 * repo root is an inert [Path] (the application layer performs no file IO against it).
 */
data class FeatureTaskRuntimeRunRequest(
  val issueKey: String,
  val workflowId: String,
  val sessionId: String,
  val runInvariants: FeatureTaskRuntimeRunInvariants,
  val invokedAgentId: String,
  val agentAssignment: FeatureTaskRuntimeAgentAssignment = FeatureTaskRuntimeAgentAssignment(),
  val environment: Map<String, String> = emptyMap(),
  val dbPathOverride: String? = null,
  val repoRoot: Path,
  /** Optional per-phase wall-clock cap forwarded to each phase agent launch. */
  val timeout: Duration? = null,
  /** Present only for non-interactive goal-runner continuation children. */
  val goalContinuation: FeatureTaskRuntimeGoalContinuationContext? = null,
  val eventSink: FeatureTaskRuntimeRunEventSink = FeatureTaskRuntimeRunEventSink.NONE,
) {
  init {
    require(issueKey.isNotBlank()) { "FeatureTaskRuntimeRunRequest.issueKey is required." }
    require(workflowId.isNotBlank()) { "FeatureTaskRuntimeRunRequest.workflowId is required." }
    require(invokedAgentId.isNotBlank()) {
      "FeatureTaskRuntimeRunRequest.invokedAgentId is required; it is the documented default agent."
    }
  }
}

data class FeatureTaskRuntimeGoalContinuationContext(
  val parentIssueKey: String,
  val subtaskId: Int,
  val goalBranch: String,
  val suppressPr: Boolean,
  val parentWorkflowId: String? = null,
  val lastResumableStep: String? = null,
) {
  init {
    require(parentIssueKey.isNotBlank()) { "parentIssueKey is required." }
    require(subtaskId > 0) { "subtaskId must be positive." }
    require(goalBranch.isNotBlank()) { "goalBranch is required." }
    parentWorkflowId?.let { require(it.isNotBlank()) { "parentWorkflowId must be non-blank when provided." } }
    lastResumableStep?.let { require(it.isNotBlank()) { "lastResumableStep must be non-blank when provided." } }
  }
}

/**
 * The terminal report of one phase-loop run. [Completed] means every phase produced schema-valid
 * output; [Blocked] means the run halted at [lastIncompletePhase] with a [blockedReason].
 */
sealed interface FeatureTaskRuntimeRunReport {
  val issueKey: String
  val workflowId: String
  val featureSize: String

  /** The non-default feature branch the run was pinned to, or null when not yet resolved. */
  val resolvedBranch: String?

  data class Completed(
    override val issueKey: String,
    override val workflowId: String,
    override val featureSize: String,
    val completedPhaseIds: List<String>,
    override val resolvedBranch: String?,
    val subtaskOutcome: FeatureTaskRuntimeSubtaskOutcome? = null,
  ) : FeatureTaskRuntimeRunReport

  data class Blocked(
    override val issueKey: String,
    override val workflowId: String,
    override val featureSize: String,
    val lastIncompletePhase: String,
    val blockedReason: String,
    val completedPhaseIds: List<String>,
    override val resolvedBranch: String?,
    val subtaskOutcome: FeatureTaskRuntimeSubtaskOutcome? = null,
  ) : FeatureTaskRuntimeRunReport {
    init {
      require(lastIncompletePhase.isNotBlank()) {
        "FeatureTaskRuntimeRunReport.Blocked.lastIncompletePhase must be non-blank."
      }
      require(blockedReason.isNotBlank()) {
        "FeatureTaskRuntimeRunReport.Blocked.blockedReason must be non-blank."
      }
    }
  }

  data class Decomposed(
    override val issueKey: String,
    override val workflowId: String,
    override val featureSize: String,
    val reason: String,
    val completedPhaseIds: List<String>,
    val parentSpecPath: String,
    val decompositionManifestPath: String,
    val subtaskSpecPaths: List<String>,
    override val resolvedBranch: String?,
  ) : FeatureTaskRuntimeRunReport {
    init {
      require(reason.isNotBlank()) { "FeatureTaskRuntimeRunReport.Decomposed.reason must be non-blank." }
      require(parentSpecPath.isNotBlank()) {
        "FeatureTaskRuntimeRunReport.Decomposed.parentSpecPath must be non-blank."
      }
      require(decompositionManifestPath.isNotBlank()) {
        "FeatureTaskRuntimeRunReport.Decomposed.decompositionManifestPath must be non-blank."
      }
      require(subtaskSpecPaths.isNotEmpty()) {
        "FeatureTaskRuntimeRunReport.Decomposed.subtaskSpecPaths must not be empty."
      }
    }
  }
}

data class FeatureTaskRuntimeSubtaskOutcome(
  val issueKey: String,
  val subtaskId: Int,
  val status: String,
  val commitSha: String?,
  val workflowId: String,
  val blockedReason: String?,
  val lastResumableStep: String,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(subtaskId > 0) { "subtaskId must be positive." }
    require(status.isNotBlank()) { "status is required." }
    require(workflowId.isNotBlank()) { "workflowId is required." }
    require(lastResumableStep.isNotBlank()) { "lastResumableStep is required." }
  }
}

/** The plan-phase stop decision: continue the loop, terminate decomposed, or block loudly. */
sealed interface FeatureTaskRuntimePlanningStopDecision {
  data object Proceed : FeatureTaskRuntimePlanningStopDecision

  data class Decomposed(
    val report: FeatureTaskRuntimeRunReport.Decomposed,
  ) : FeatureTaskRuntimePlanningStopDecision

  data class Blocked(val reason: String) : FeatureTaskRuntimePlanningStopDecision
}

/** Typed observability events emitted at phase boundaries. */
sealed interface FeatureTaskRuntimeRunEvent {
  val workflowId: String
  val phaseId: String

  data class RunStarted(
    override val workflowId: String,
    val featureSize: String,
  ) : FeatureTaskRuntimeRunEvent {
    override val phaseId: String = "run"
  }

  /**
   * Emitted once when the runtime establishes the run's feature branch before the first
   * file-mutating phase. [created] is true when the runtime created and switched to the branch,
   * false when it reused an already-checked-out non-default branch (including a resume re-attach).
   */
  data class BranchResolved(
    override val workflowId: String,
    override val phaseId: String,
    val branch: String,
    val created: Boolean,
    val reused: Boolean,
  ) : FeatureTaskRuntimeRunEvent

  /**
   * Emitted once when the runtime fails to establish the run's feature branch (missing/dirty git,
   * denied/non-landing checkout, unreadable HEAD, a deleted or unverifiable persisted branch, a
   * protected/wrong landed branch, or a branch that could not be durably recorded). Symmetric with
   * [PhaseBlocked]: the same block is also persisted as a durable blocked record and a ledger entry
   * so the failure is visible to status queries and the audit trail, not only the event stream.
   */
  data class BranchSetupBlocked(
    override val workflowId: String,
    override val phaseId: String,
    val blockedReason: String,
  ) : FeatureTaskRuntimeRunEvent

  data class PhaseStarted(
    override val workflowId: String,
    override val phaseId: String,
    val resolvedAgentId: String,
    val attemptCount: Int,
    val resumed: Boolean,
  ) : FeatureTaskRuntimeRunEvent

  data class PhaseFixLoopIteration(
    override val workflowId: String,
    override val phaseId: String,
    val resolvedAgentId: String,
    val attemptCount: Int,
    val fixLoopIteration: Int,
  ) : FeatureTaskRuntimeRunEvent

  data class PhaseCompleted(
    override val workflowId: String,
    override val phaseId: String,
    val resolvedAgentId: String,
    val attemptCount: Int,
  ) : FeatureTaskRuntimeRunEvent

  data class PhaseBlocked(
    override val workflowId: String,
    override val phaseId: String,
    val resolvedAgentId: String,
    val attemptCount: Int,
    val blockedReason: String,
  ) : FeatureTaskRuntimeRunEvent

  data class DecomposedAtPlanning(
    override val workflowId: String,
    override val phaseId: String,
    val reason: String,
    val subtaskCount: Int,
    val parentSpecPath: String,
    val decompositionManifestPath: String,
  ) : FeatureTaskRuntimeRunEvent
}

fun interface FeatureTaskRuntimeRunEventSink {
  fun emit(event: FeatureTaskRuntimeRunEvent)

  companion object {
    val NONE: FeatureTaskRuntimeRunEventSink = FeatureTaskRuntimeRunEventSink {}
  }
}

/** Outcome of the bounded fix-loop policy for a failed phase attempt. */
sealed interface FeatureTaskRuntimeFixLoopDecision {
  data class Retry(val nextIteration: Int, val fixLoopIteration: Int) : FeatureTaskRuntimeFixLoopDecision

  data class Block(val blockedReason: String) : FeatureTaskRuntimeFixLoopDecision
}

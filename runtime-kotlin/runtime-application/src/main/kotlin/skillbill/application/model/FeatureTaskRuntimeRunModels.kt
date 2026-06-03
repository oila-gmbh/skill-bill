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

/**
 * The terminal report of one phase-loop run. [Completed] means every phase produced schema-valid
 * output; [Blocked] means the run halted at [lastIncompletePhase] with a [blockedReason].
 */
sealed interface FeatureTaskRuntimeRunReport {
  val issueKey: String
  val workflowId: String

  data class Completed(
    override val issueKey: String,
    override val workflowId: String,
    val completedPhaseIds: List<String>,
  ) : FeatureTaskRuntimeRunReport

  data class Blocked(
    override val issueKey: String,
    override val workflowId: String,
    val lastIncompletePhase: String,
    val blockedReason: String,
    val completedPhaseIds: List<String>,
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
}

/** Typed observability events emitted at phase boundaries. */
sealed interface FeatureTaskRuntimeRunEvent {
  val workflowId: String
  val phaseId: String

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

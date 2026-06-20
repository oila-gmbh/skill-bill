package skillbill.application.goalrunner

import skillbill.application.model.GoalRunnerRunRequest
import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerWorkflowProgress
import java.time.Instant

internal class GoalRunnerObservabilityEmitter(
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  request: GoalRunnerRunRequest,
) {
  private val dbPathOverride: String? = request.dbPathOverride
  private var sequence: Int = request.observabilitySequenceStart

  fun recordLaunchLifecycle(
    subject: GoalRunnerObservabilitySubject,
    action: String,
    progress: GoalRunnerWorkflowProgress?,
    launchOutcome: AgentRunLaunchOutcome,
  ) {
    recordStart(subject, action, progress)
    progress?.let { child -> recordProgress(subject, child) }
    (launchOutcome as? AgentRunLaunchFacts)?.let { facts ->
      facts.liveness?.let { recordLiveness(subject, progress, facts) }
      recordOutputSummary(subject, progress, facts)
    }
  }

  fun record(subject: GoalRunnerObservabilitySubject, signal: GoalRunnerObservabilitySignal) {
    runCatching {
      outcomeStore.recordObservabilityEvent(
        request = GoalRunnerObservabilityRecordRequest(
          workflowId = subject.workflowId,
          issueKey = subject.issueKey,
          subtaskId = subject.subtaskId,
          workflowPhase = signal.workflowPhase.takeIf(String::isNotBlank) ?: "goal_runner_supervision",
          workerRole = "goal_runner_supervisor",
          livenessClass = signal.livenessClass,
          activitySummary = signal.activitySummary.takeIf(String::isNotBlank) ?: signal.livenessClass,
          sequenceNumber = sequence++,
          timestamp = Instant.now().toString(),
        ),
        dbPathOverride = dbPathOverride,
      )
    }
  }

  private fun recordStart(
    subject: GoalRunnerObservabilitySubject,
    action: String,
    progress: GoalRunnerWorkflowProgress?,
  ) {
    // SKILL-64 Subtask 3 (AC24): prefer the authoritative durable step; fall
    // back to "preplan" only when no durable step exists yet for the child.
    record(
      subject = subject,
      signal = GoalRunnerObservabilitySignal(
        workflowPhase = progress?.currentStepId?.takeIf(String::isNotBlank) ?: "preplan",
        livenessClass = if (action == "resume") "resume" else "subtask_start",
        activitySummary = "Goal runner ${action}s subtask ${subject.subtaskId}.",
      ),
    )
  }

  private fun recordProgress(subject: GoalRunnerObservabilitySubject, child: GoalRunnerWorkflowProgress) {
    record(
      subject = subject,
      signal = GoalRunnerObservabilitySignal(
        workflowPhase = child.currentStepId,
        livenessClass = "phase_change",
        activitySummary = "Child workflow is at step ${child.currentStepId}.",
      ),
    )
    child.latestLivenessSignal?.takeIf(String::isNotBlank)?.let { signal ->
      record(
        subject = subject,
        signal = GoalRunnerObservabilitySignal(
          workflowPhase = child.currentStepId,
          livenessClass = "heartbeat",
          activitySummary = signal,
        ),
      )
    }
  }

  private fun recordLiveness(
    subject: GoalRunnerObservabilitySubject,
    progress: GoalRunnerWorkflowProgress?,
    facts: AgentRunLaunchFacts,
  ) {
    val liveness = facts.liveness ?: return
    val phase = liveness.workflowStep ?: progress?.currentStepId ?: liveness.phase
    record(
      subject = subject,
      signal = GoalRunnerObservabilitySignal(
        workflowPhase = phase,
        livenessClass = "heartbeat",
        activitySummary = "process_state=${liveness.processState}; reason=${liveness.reason}",
      ),
    )
    liveness.lastFileActivityAt?.takeIf(String::isNotBlank)?.let { at ->
      record(
        subject = subject,
        signal = GoalRunnerObservabilitySignal(
          workflowPhase = phase,
          livenessClass = "file_activity",
          activitySummary = liveness.lastFileActivityLabel ?: "file activity observed at $at",
        ),
      )
    }
  }

  private fun recordOutputSummary(
    subject: GoalRunnerObservabilitySubject,
    progress: GoalRunnerWorkflowProgress?,
    facts: AgentRunLaunchFacts,
  ) {
    // Persist a bounded stderr tail alongside the counts: a char count alone makes a
    // no-terminal-outcome stall undiagnosable after the fact (the body is the only signal that
    // distinguishes a crash from a usage limit). The store is local to ~/.skill-bill.
    val stderrTail = facts.stderr
      .takeLast(GoalRunnerLaunchFacts.STDERR_TAIL_MAX_CHARS)
      .takeIf(String::isNotBlank)
      ?.let { tail -> "; stderr_tail=$tail" }
      .orEmpty()
    record(
      subject = subject,
      signal = GoalRunnerObservabilitySignal(
        workflowPhase = progress?.currentStepId ?: facts.liveness?.workflowStep ?: "goal_runner_supervision",
        livenessClass = "worker_output_summary",
        activitySummary = "stdout_chars=${facts.stdout.length}; stderr_chars=${facts.stderr.length}; " +
          "exit_status=${facts.exitStatus ?: "none"}$stderrTail",
      ),
    )
  }
}

internal data class GoalRunnerObservabilitySubject(
  val workflowId: String,
  val issueKey: String,
  val subtaskId: Int,
)

internal data class GoalRunnerObservabilitySignal(
  val workflowPhase: String,
  val livenessClass: String,
  val activitySummary: String,
)

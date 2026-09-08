package skillbill.application.goalrunner

import skillbill.contracts.JsonSupport
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState

internal data class GoalRunnerLaunchReconciliation(
  val refreshed: GoalRunnerManifestState,
  val reconciled: GoalRunnerReconciledOutcome,
  val launchOutcome: AgentRunLaunchOutcome,
  val diagnostics: GoalRunnerLaunchDiagnostics? = null,
)

internal data class GoalRunnerLaunchDiagnostics(
  val diagnosticClass: String,
  val recoverableJsonPresent: Boolean,
  val nextSafeAction: String,
)

internal data class GoalRunnerMissingResultPrefixRecovery(
  val storedOutcome: GoalRunnerStoredOutcome?,
  val diagnostics: GoalRunnerLaunchDiagnostics,
)

internal data class GoalRunnerMissingResultPrefixCandidate(
  val output: Map<String, Any?>,
  val lastResumableStep: String?,
  val workflowId: String?,
)

internal fun missingResultPrefixDiagnostics(lastResumableStep: String?): GoalRunnerLaunchDiagnostics =
  GoalRunnerLaunchDiagnostics(
    diagnosticClass = "missing_result_prefix",
    recoverableJsonPresent = true,
    nextSafeAction = if (lastResumableStep.isNullOrBlank()) {
      "continue_inline"
    } else {
      "resume_from_last_resumable_step"
    },
  )

internal fun missingPrefixRecoveryCandidate(
  reconciled: GoalRunnerReconciledOutcome,
  launchOutcome: AgentRunLaunchOutcome,
): GoalRunnerMissingResultPrefixCandidate? = (reconciled as? GoalRunnerReconciledOutcome.Stop)
  ?.takeIf { stop -> stop.reason == GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME }
  ?.let { stop ->
    (launchOutcome as? AgentRunLaunchFacts)?.let { facts ->
      terminalJsonObjectWithoutResultPrefix(facts.stdout, facts.stderr)?.let { output ->
        GoalRunnerMissingResultPrefixCandidate(
          output = output,
          lastResumableStep = stop.lastResumableStep,
          workflowId = facts.liveness?.workflowId?.takeIf(String::isNotBlank),
        )
      }
    }
  }

internal fun malformedResultJsonDiagnostics(
  reconciled: GoalRunnerReconciledOutcome,
  launchOutcome: AgentRunLaunchOutcome,
): GoalRunnerLaunchDiagnostics? = (reconciled as? GoalRunnerReconciledOutcome.Stop)
  ?.let { launchOutcome as? AgentRunLaunchFacts }
  ?.takeIf { facts -> childOutputHasJsonLikeContent(facts.stdout, facts.stderr) }
  ?.takeIf { facts -> terminalJsonObjectWithoutResultPrefix(facts.stdout, facts.stderr) == null }
  ?.let {
    GoalRunnerLaunchDiagnostics(
      diagnosticClass = "malformed_result_json",
      recoverableJsonPresent = false,
      nextSafeAction = "inspect_child_output_then_resume",
    )
  }

fun terminalJsonObjectWithoutResultPrefix(stdout: String, stderr: String): Map<String, Any?>? {
  val combined = listOf(stdout, stderr)
    .filter(String::isNotBlank)
    .joinToString("\n")
  val candidate = combined
    .takeUnless { it.contains("RESULT:") }
    ?.let(::topLevelJsonObjectCandidates)
    ?.singleOrNull()
  return candidate
    ?.let(JsonSupport::parseObjectOrNull)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    ?.takeIf { it.isImplementationReturnContract() || it.isRuntimeTerminalEnvelope() }
}

fun childOutputHasJsonLikeContent(stdout: String, stderr: String): Boolean =
  listOf(stdout, stderr).any { output -> output.contains('{') || output.contains('}') || output.contains("RESULT:") }

fun Map<String, Any?>.isImplementationReturnContract(): Boolean = keys.containsAll(
  setOf(
    "tasks_completed",
    "files_created",
    "files_modified",
    "tests_written",
    "plan_deviation_notes",
    "notes_for_review",
  ),
)

fun Map<String, Any?>.isRuntimeTerminalEnvelope(): Boolean =
  this["status"]?.toString() in setOf("complete", "completed", "blocked", "failed", "timeout", "timed_out") &&
    this["workflow_id"]?.toString().orEmpty().isNotBlank()

fun topLevelJsonObjectCandidates(text: String): List<String> {
  val candidates = mutableListOf<String>()
  var depth = 0
  var start = -1
  var inString = false
  var escaped = false
  text.forEachIndexed { index, char ->
    when {
      escaped -> escaped = false
      inString && char == '\\' -> escaped = true
      char == '"' -> inString = !inString
      inString -> Unit
      char == '{' -> {
        if (depth == 0) {
          start = index
        }
        depth += 1
      }
      char == '}' && depth > 0 -> {
        depth -= 1
        if (depth == 0 && start >= 0) {
          candidates += text.substring(start, index + 1)
          start = -1
        }
      }
    }
  }
  return candidates
}

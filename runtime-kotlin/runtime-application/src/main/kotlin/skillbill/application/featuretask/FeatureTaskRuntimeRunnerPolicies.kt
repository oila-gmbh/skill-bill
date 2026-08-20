package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.contracts.JsonSupport
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration

internal const val STATUS_RUNNING = "running"
internal const val STATUS_COMPLETED = "completed"
internal const val STATUS_BLOCKED = "blocked"

// SKILL-141's non-terminal resumable workflow status, consumed rather than forked: an unresolved
// Blocker disposition pauses the child here instead of blocking it.
internal const val STATUS_PAUSED = "paused"

// The operator's abandon_subtask decision ends the subtask without repairing it.
internal const val STATUS_ABANDONED = "abandoned"
internal const val BRANCH_SETUP_AGENT_ID = "branch-setup"
internal const val SCHEMA_GATE_DETAIL_MAX_CHARS = 500

// The phase-output envelope's own status vocabulary, distinct from the durable phase-row status above.
internal const val PHASE_OUTPUT_STATUS_COMPLETED = "completed"

internal val NON_FILE_MUTATING_PHASES = setOf(
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
)

internal fun serializeTokenData(accumulator: Map<String, Pair<Int, Int>>): Pair<String?, Int?> {
  if (accumulator.isEmpty()) return null to null
  val breakdown = accumulator.mapValues { (_, pair) ->
    mapOf("estimated_input_tokens" to pair.first, "estimated_output_tokens" to pair.second)
  }
  val total = accumulator.values.sumOf { (input, output) -> input + output }
  return JsonSupport.mapToJsonString(breakdown) to total
}

internal fun isFileMutating(phaseId: String): Boolean = phaseId !in NON_FILE_MUTATING_PHASES

internal fun transitionsFor(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimeTransitionDeclaration =
  request.transitionsOverride ?: phasesFor(request).let { phases ->
    FeatureTaskRuntimeTransitionDeclaration(
      forwardPhaseIds = phases,
      // Backward edges whose endpoints both survive the goal-continuation truncation. An edge naming a
      // phase the resolved pipeline dropped would fail the declaration's endpoint invariant here,
      // outside the runner's failure handling. A regeneration edge whose producer was truncated away
      // therefore simply does not exist for this run: the launch seam finds no matching edge and
      // blocks durably with an actionable reason instead of attempting an impossible re-entry.
      backwardEdges = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.backwardEdges
        .filter { it.fromPhaseId in phases && it.destinationPhaseId in phases },
      loopOnlyPhaseIds = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.loopOnlyPhaseIds
        .filter { it in phases }.toSet(),
      // Gates whose endpoints both survive the goal-continuation truncation. A gate naming a phase
      // the resolved pipeline dropped would fail the declaration's precedes-invariant here, outside
      // the runner's failure handling, so a truncation point turns into a crash rather than a gate.
      entryGates = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.entryGates
        .filter { it.phaseId in phases && it.requiredPhaseId in phases },
      loopOnlySuccessors = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.loopOnlySuccessors
        .filterKeys { it in phases }
        .filterValues { it in phases },
    )
  }

internal fun phasesFor(request: FeatureTaskRuntimeRunRequest): List<String> {
  val phases = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds
  return if (isGoalContinuationRun(request)) {
    phases.takeWhile { it != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR }
  } else {
    phases
  }
}

internal fun mutatingReconciliationGateReason(phaseId: String, outputMap: Map<String, Any?>): String? {
  if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(phaseId)) return null
  // Only a completion claim owes a reconciliation report. A retryable blocked or failed envelope is a
  // schema-valid terminal outcome that never claimed the tree reached target, so charging it with a
  // missing reconciliation report converted it into a schema-gate rejection and denied it the terminal
  // path it belongs on.
  if (outputMap["status"] != PHASE_OUTPUT_STATUS_COMPLETED) return null
  val producedOutputs = outputMap["produced_outputs"] as? Map<*, *>
  val nestedReconciled = (producedOutputs?.get("reconciled_state") as? Map<*, *>)?.get("reconciled")
  val reconciled = nestedReconciled == true || producedOutputs?.get("reconciled") == true
  return if (reconciled) {
    null
  } else {
    "Mutating phase '$phaseId' reported 'completed' without a reconciliation report proving it " +
      "reconciled the working tree to target: produced_outputs must carry 'reconciled_state' (or a " +
      "'reconciled' entry) with 'reconciled' set to true. The idempotency contract is verified, not " +
      "assumed; a silent skip fails the schema gate."
  }
}

internal fun boundedSchemaGateDetail(validationReason: String): String =
  if (validationReason.length <= SCHEMA_GATE_DETAIL_MAX_CHARS) {
    validationReason
  } else {
    validationReason.take(SCHEMA_GATE_DETAIL_MAX_CHARS) + "… [truncated]"
  }

internal fun withSchemaGateDetail(policyReason: String, validationReason: String): String =
  "$policyReason Last schema-gate failure: ${boundedSchemaGateDetail(validationReason)}"

internal fun nonRetryingPhaseSchemaBlockReason(phaseId: String): String =
  "Phase '$phaseId' produced schema-invalid output and does not participate in a fix loop; " +
    "the run blocks rather than advancing on invalid output."

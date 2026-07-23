package skillbill.application

import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffProjectionValidator
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef

/**
 * Reads the receipt body a phase was actually delivered for one producing phase. Tests go through
 * the validated envelope rather than a payload map, so an assertion cannot accidentally observe
 * private evidence the phase never received.
 */
internal fun FeatureTaskRuntimePhaseLaunchBriefing.upstreamReceipt(producingPhaseId: String): String? =
  handoffEnvelope.projections
    .firstOrNull { projection ->
      (projection.sourceRef as? FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput)
        ?.producingPhaseId == producingPhaseId
    }
    ?.fields
    ?.firstOrNull { it.name == FeatureTaskRuntimeHandoffProjectionValidator.PHASE_OUTPUT_RECEIPT_FIELD }
    ?.let { (it.value as? FeatureTaskRuntimeHandoffProjectionValue.Text)?.text }

internal fun FeatureTaskRuntimePhaseLaunchBriefing.requireUpstreamReceipt(producingPhaseId: String): String =
  requireNotNull(upstreamReceipt(producingPhaseId)) {
    "Briefing for phase '$phaseId' carries no delivered receipt for producing phase '$producingPhaseId'."
  }

internal fun FeatureTaskRuntimePhaseLaunchBriefing.hasUpstreamReceipt(producingPhaseId: String): Boolean =
  upstreamReceipt(producingPhaseId) != null

/** Phases whose prompts omit the acceptance contract and policy mandates by allowlist. */
internal val FINALIZATION_PHASE_IDS: Set<String> = setOf("write_history", "commit_push", "pr")

/**
 * Canonical `produced_outputs` bodies for the phases that feed the bounded planning projections on the
 * preplan->plan, plan->implement, and plan+implement->audit edges. Fixtures that seed these phases must
 * carry the declared projection shape or the projection loud-fails at launch.
 */
internal object PlanningProjectionFixtures {
  const val PREPLAN_DIGEST: String =
    """{"projection_kind":"preplanning_digest","affected_boundaries":["runtime-application"],""" +
      """"risks":["Fixture risk."],""" +
      """"rollout":{"flag_required":false,"flag_pattern":"none","notes":"No flag needed."},""" +
      """"validation_strategy":["Focused runtime tests."]}"""

  const val EXECUTABLE_PLAN: String =
    """{"projection_kind":"executable_plan","mode":"direct","tasks":[{"task_id":"task-1",""" +
      """"description":"Fixture task.","criterion_refs":["AC-001"],""" +
      """"target_paths_or_symbols":["src/Foo.kt"],"test_obligations":["Focused test."]}],""" +
      """"validation_strategy":["Focused runtime tests."]}"""

  const val IMPLEMENTATION_RECEIPT: String =
    """{"projection_kind":"implementation_receipt","completed_task_ids":["task-1"],""" +
      """"changed_paths":["src/Foo.kt"],""" +
      """"tests_executed":[{"name":"FooTest","outcome":"passed"}],""" +
      """"reconciliation_evidence":{"reconciled":true,"evidence":"Fixture tree at target state."},""" +
      """"repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},"reconciled_state":{"reconciled":true}}"""

  /**
   * The receipt's declared fields as a trailing-comma fragment, for implement fixtures that build their
   * own `produced_outputs` body (repair-item results, unresolvable repair) and must also satisfy the
   * bounded implementation-receipt projection audit consumes.
   */
  const val RECEIPT_FIELDS: String =
    """"projection_kind":"implementation_receipt","completed_task_ids":["task-1"],""" +
      """"changed_paths":["src/Foo.kt"],"tests_executed":[{"name":"FooTest","outcome":"passed"}],""" +
      """"reconciliation_evidence":{"reconciled":true,"evidence":"Fixture tree at target state."},""" +
      """"repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},"""

  /** The projection body for [phaseId], or null when that phase feeds no planning projection edge. */
  fun producedOutputsOrNull(phaseId: String): String? = when (phaseId) {
    "preplan" -> PREPLAN_DIGEST
    "plan" -> EXECUTABLE_PLAN
    "implement" -> IMPLEMENTATION_RECEIPT
    else -> null
  }
}

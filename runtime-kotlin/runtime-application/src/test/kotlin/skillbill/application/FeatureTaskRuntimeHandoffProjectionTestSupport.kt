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

/** Finalization phases whose prompts omit the acceptance contract; PR explicitly requires it. */
internal val FINALIZATION_PHASE_IDS: Set<String> = setOf("write_history", "commit_push")

/**
 * Canonical `produced_outputs` bodies for the phases that feed the bounded planning projections on the
 * preplan->plan, plan->implement, and plan+implement->audit edges. Fixtures that seed these phases must
 * carry the declared projection shape or the projection loud-fails at launch.
 */
internal object PlanningProjectionFixtures {
  const val PREPLAN_DIGEST: String =
    """{"value":"Fixture preplan prose for downstream plan."}"""

  const val PLAN_PROSE: String =
    """{"value":"Fixture plan prose for downstream implement and audit."}"""

  const val IMPLEMENT_PROSE: String =
    """{"value":"Fixture implement prose for downstream audit."}"""

  const val IMPLEMENT_PROSE_FIELDS: String =
    """"value":"Fixture implement prose for downstream audit.","""

  fun producedOutputsOrNull(phaseId: String): String? = when (phaseId) {
    "preplan" -> PREPLAN_DIGEST
    "plan" -> PLAN_PROSE
    "implement" -> IMPLEMENT_PROSE
    else -> null
  }
}

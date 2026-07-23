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

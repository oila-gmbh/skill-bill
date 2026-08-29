package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PHASE_HANDOFF_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskRuntimePhaseHandoffSchemaError
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.goal.model.ValidationDepth

/**
 * Typed handoff-projection primitives. Together they replace the generic upstream-payload map with a
 * four-part boundary: private evidence stays in the durable phase record, the consumer projection is
 * the only prompt-visible view of it, repository-derived context arrives as a checkpoint, and
 * phase-local instructions are rendered from allowlisted run invariants.
 *
 * Every type here is closed (sealed or enum) and immutable, so a projection set is a design-time
 * property of the workflow. There is deliberately no API that lets an executing agent, a phase
 * output, a resumed prompt, or a caller argument add a source or widen a projection.
 */

/** Names a projection's single source. Closed set: a new source kind requires a code change. */
sealed interface FeatureTaskRuntimeHandoffSourceRef {
  val wireValue: String

  /** The latest recorded output of one producing phase, delivered as a bounded receipt. */
  data class UpstreamPhaseOutput(val producingPhaseId: String) : FeatureTaskRuntimeHandoffSourceRef {
    init {
      require(producingPhaseId.isNotBlank()) {
        "FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput.producingPhaseId must be non-blank."
      }
    }

    override val wireValue: String get() = "$UPSTREAM_PHASE_OUTPUT_PREFIX$producingPhaseId"
  }

  /** One allowlisted run-invariant field. */
  data class RunInvariantField(val invariantField: FeatureTaskRuntimeRunInvariantPromptField) :
    FeatureTaskRuntimeHandoffSourceRef {
    override val wireValue: String get() = "$RUN_INVARIANT_FIELD_PREFIX${invariantField.wireValue}"
  }

  /** The ceremony scaling derived from the resolved feature size. */
  object DerivedCeremonyScaling : FeatureTaskRuntimeHandoffSourceRef {
    override val wireValue: String get() = DERIVED_CEREMONY_SCALING_WIRE
  }

  /**
   * The runtime-derived shared review evidence artifact for the current checkpoint. It has no producing
   * phase: the runtime derives it from the repository, so it is its own source kind rather than an
   * upstream phase output.
   */
  object SharedReviewEvidence : FeatureTaskRuntimeHandoffSourceRef {
    override val wireValue: String get() = SHARED_REVIEW_EVIDENCE_WIRE
  }

  object RepairLedger : FeatureTaskRuntimeHandoffSourceRef {
    override val wireValue: String get() = REPAIR_LEDGER_WIRE
  }

  /**
   * The runtime-derived bounded prior-gap memory for an `audit_gap` remediation round. Like shared
   * review evidence it has no producing phase: the runtime derives it from the durable audit and
   * implement outputs, so it is its own source kind rather than an upstream phase output.
   */
  object PriorGapMemory : FeatureTaskRuntimeHandoffSourceRef {
    override val wireValue: String get() = PRIOR_GAP_MEMORY_WIRE
  }

  /** Hydrated content of one selected add-on, budgeted separately from phase receipts. */
  data class AddonContentRef(val slug: String) : FeatureTaskRuntimeHandoffSourceRef {
    init {
      require(slug.isNotBlank()) { "FeatureTaskRuntimeHandoffSourceRef.AddonContentRef.slug must be non-blank." }
    }

    override val wireValue: String get() = "$ADDON_CONTENT_PREFIX$slug"
  }

  companion object {
    const val UPSTREAM_PHASE_OUTPUT_PREFIX: String = "upstream_phase_output:"
    const val RUN_INVARIANT_FIELD_PREFIX: String = "run_invariant_field:"
    const val ADDON_CONTENT_PREFIX: String = "addon_content:"
    const val DERIVED_CEREMONY_SCALING_WIRE: String = "derived_ceremony_scaling"
    const val SHARED_REVIEW_EVIDENCE_WIRE: String = "shared_review_evidence"
    const val REPAIR_LEDGER_WIRE: String = "repair_ledger"
    const val PRIOR_GAP_MEMORY_WIRE: String = "prior_gap_memory"

    fun fromWire(value: String): FeatureTaskRuntimeHandoffSourceRef = when {
      value == DERIVED_CEREMONY_SCALING_WIRE -> DerivedCeremonyScaling
      value == SHARED_REVIEW_EVIDENCE_WIRE -> SharedReviewEvidence
      value == REPAIR_LEDGER_WIRE -> RepairLedger
      value == PRIOR_GAP_MEMORY_WIRE -> PriorGapMemory
      value.startsWith(UPSTREAM_PHASE_OUTPUT_PREFIX) ->
        UpstreamPhaseOutput(value.removePrefix(UPSTREAM_PHASE_OUTPUT_PREFIX))
      value.startsWith(RUN_INVARIANT_FIELD_PREFIX) ->
        RunInvariantField(
          FeatureTaskRuntimeRunInvariantPromptField.fromWire(value.removePrefix(RUN_INVARIANT_FIELD_PREFIX)),
        )
      value.startsWith(ADDON_CONTENT_PREFIX) -> AddonContentRef(value.removePrefix(ADDON_CONTENT_PREFIX))
      else -> unrecognizedHandoffWireValue("source ref", value)
    }
  }

  @OpenBoundaryMap("Feature-task-runtime phase-handoff declaration source wire seam")
  fun toDeclarationMap(): Map<String, String> = when (this) {
    is UpstreamPhaseOutput -> mapOf("kind" to "upstream_phase_output", "id" to producingPhaseId)
    is RunInvariantField -> mapOf("kind" to "run_invariant_field", "id" to invariantField.wireValue)
    DerivedCeremonyScaling -> mapOf("kind" to "derived_ceremony_scaling", "id" to "ceremony_scaling")
    SharedReviewEvidence -> mapOf("kind" to SHARED_REVIEW_EVIDENCE_WIRE, "id" to SHARED_REVIEW_EVIDENCE_WIRE)
    RepairLedger -> mapOf("kind" to REPAIR_LEDGER_WIRE, "id" to REPAIR_LEDGER_WIRE)
    PriorGapMemory -> mapOf("kind" to PRIOR_GAP_MEMORY_WIRE, "id" to PRIOR_GAP_MEMORY_WIRE)
    is AddonContentRef -> mapOf("kind" to "addon_content", "id" to slug)
  }
}

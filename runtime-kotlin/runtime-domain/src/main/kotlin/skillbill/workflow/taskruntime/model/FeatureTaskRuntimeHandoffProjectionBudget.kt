package skillbill.workflow.taskruntime.model

/**
 * Per-projection budget. Both dimensions are counted before prompt serialization; an overflow is a
 * rejection, never a truncation, so the consumer either receives the whole projection or none of it.
 */
data class FeatureTaskRuntimeHandoffProjectionBudget(
  val maxUtf8Bytes: Int,
  val maxCollectionItems: Int,
) {
  init {
    require(maxUtf8Bytes > 0) {
      "FeatureTaskRuntimeHandoffProjectionBudget.maxUtf8Bytes must be positive, was $maxUtf8Bytes."
    }
    require(maxCollectionItems > 0) {
      "FeatureTaskRuntimeHandoffProjectionBudget.maxCollectionItems must be positive, was $maxCollectionItems."
    }
  }

  companion object {
    /**
     * Sized against recorded runtime phase outputs: across 239 durable outputs no phase other than
     * `preplan` exceeded 20,844 UTF-8 bytes, so this leaves better than 3x headroom while a coarse
     * whole-receipt projection is still the delivered shape. A rejection here means a phase output
     * grew far beyond every observed size, not that an ordinary run outgrew its budget.
     */
    val PHASE_RECEIPT: FeatureTaskRuntimeHandoffProjectionBudget =
      FeatureTaskRuntimeHandoffProjectionBudget(maxUtf8Bytes = 65_536, maxCollectionItems = 64)

    /**
     * The preplanning digest is the one phase output that routinely runs an order of magnitude
     * larger than the rest; the same 239 recorded outputs put its maximum at 131,901 UTF-8 bytes.
     * Its single consumer (`plan`) therefore gets its own budget rather than forcing every edge up.
     */
    val PREPLAN_DIGEST_RECEIPT: FeatureTaskRuntimeHandoffProjectionBudget =
      FeatureTaskRuntimeHandoffProjectionBudget(maxUtf8Bytes = 196_608, maxCollectionItems = 64)

    /**
     * Sized against the manifest-declared feature-task add-on consumers: the largest shipped consumer
     * set (the kmp pack's seven `feature-task` add-ons) sums to 31,863 UTF-8 bytes, so 96 KiB leaves
     * better than 3x headroom while add-on content stays bounded independently of the phase-receipt
     * budget. A rejection here means an add-on payload grew far beyond every shipped consumer set, not
     * that an ordinary run outgrew its budget.
     */
    val ADDON_CONTENT: FeatureTaskRuntimeHandoffProjectionBudget =
      FeatureTaskRuntimeHandoffProjectionBudget(maxUtf8Bytes = 98_304, maxCollectionItems = 16)

    /**
     * The bounded planning projections deliver many typed lists rather than one whole-receipt text
     * field, so [PHASE_RECEIPT]'s item cap — sized when a projection was worth a single item — would
     * reject an ordinary feature's implementation receipt long before its byte budget was near.
     *
     * The cap is therefore derived from the projections' own per-list caps, which the schema repeats
     * as `maxItems`: the widest variant is the implementation receipt, at one `changed_paths` list,
     * six ordinary lists, and its two scalar fields. Model, schema, and budget agree by construction,
     * so a schema-valid projection can never overflow the budget it is delivered under, and an
     * overflow here means a producer bypassed the model's own validation.
     */
    val PLANNING_PROJECTION: FeatureTaskRuntimeHandoffProjectionBudget =
      FeatureTaskRuntimeHandoffProjectionBudget(
        maxUtf8Bytes = 196_608,
        maxCollectionItems = FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT +
          (IMPLEMENTATION_RECEIPT_ORDINARY_LIST_FIELDS * FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT) +
          IMPLEMENTATION_RECEIPT_SCALAR_FIELDS,
      )

    /** completed_task_ids, tests_added, tests_updated, tests_executed, deviations, unresolved_items. */
    private const val IMPLEMENTATION_RECEIPT_ORDINARY_LIST_FIELDS: Int = 6

    /** reconciliation_evidence and repository_checkpoint. */
    private const val IMPLEMENTATION_RECEIPT_SCALAR_FIELDS: Int = 2
  }
}

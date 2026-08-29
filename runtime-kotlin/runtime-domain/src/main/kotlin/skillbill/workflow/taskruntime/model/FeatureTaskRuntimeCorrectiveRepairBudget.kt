package skillbill.workflow.taskruntime.model

/**
 * Named response, prompt, and collection budgets for corrective repair. Limits are validated before
 * prompt rendering; an overflow never silently truncates while claiming exact inclusion.
 */
data class FeatureTaskRuntimeCorrectiveRepairBudget(
  val maxResponseUtf8Bytes: Int,
  val maxPromptUtf8Bytes: Int,
  val maxCollectionItems: Int,
) {
  init {
    require(maxResponseUtf8Bytes > 0) {
      "FeatureTaskRuntimeCorrectiveRepairBudget.maxResponseUtf8Bytes must be positive, was $maxResponseUtf8Bytes."
    }
    require(maxPromptUtf8Bytes > 0) {
      "FeatureTaskRuntimeCorrectiveRepairBudget.maxPromptUtf8Bytes must be positive, was $maxPromptUtf8Bytes."
    }
    require(maxCollectionItems > 0) {
      "FeatureTaskRuntimeCorrectiveRepairBudget.maxCollectionItems must be positive, was $maxCollectionItems."
    }
    require(maxPromptUtf8Bytes >= maxResponseUtf8Bytes) {
      "FeatureTaskRuntimeCorrectiveRepairBudget.maxPromptUtf8Bytes ($maxPromptUtf8Bytes) must be at least " +
        "maxResponseUtf8Bytes ($maxResponseUtf8Bytes) so an exact body can be framed."
    }
  }

  /**
   * Rejects a projection that would carry more discrete items than [maxCollectionItems]. Called at the
   * typed context/projection boundary before any prompt rendering so an oversized collection never
   * reaches the agent as a silently truncated list.
   */
  fun requireCollectionWithinLimit(itemCount: Int, label: String = "corrective-repair projection") {
    require(itemCount >= 0) {
      "FeatureTaskRuntimeCorrectiveRepairBudget collection count for $label must be non-negative, was $itemCount."
    }
    require(itemCount <= maxCollectionItems) {
      "FeatureTaskRuntimeCorrectiveRepairBudget: $label carries $itemCount items against the " +
        "$maxCollectionItems-item collection budget; the runtime rejects rather than truncating."
    }
  }

  companion object {
    /**
     * Response body aligns with [FeatureTaskRuntimeHandoffProjectionBudget.PHASE_RECEIPT] so an ordinary
     * phase envelope fits when unchanged. Prompt budget leaves framing and payload-free guidance headroom
     * without admitting unbounded growth.
     */
    val DEFAULT: FeatureTaskRuntimeCorrectiveRepairBudget =
      FeatureTaskRuntimeCorrectiveRepairBudget(
        maxResponseUtf8Bytes = MAX_RESPONSE_UTF8_BYTES,
        maxPromptUtf8Bytes = MAX_PROMPT_UTF8_BYTES,
        maxCollectionItems = MAX_COLLECTION_ITEMS,
      )

    const val MAX_RESPONSE_UTF8_BYTES: Int = 65_536
    const val MAX_PROMPT_UTF8_BYTES: Int = 98_304
    const val MAX_COLLECTION_ITEMS: Int = 16
  }
}

package skillbill.workflow.taskruntime.model

/**
 * The bounded prior-gap memory an `audit_gap` remediation round carries into the next implement and
 * audit briefings. It records, per round: the unmet criterion ref+note lines the audit that fired the
 * edge reported, which of those the subsequent implement receipt claimed to address (joined through
 * the plan's task-to-criterion mapping), and which criterion refs are "sticky" because the last two
 * consecutive audits both reported them unmet.
 *
 * The model is deliberately a bounded projection, not a transcript: every list is capped at
 * [FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT] entries and every note line at
 * [FEATURE_TASK_RUNTIME_AUDIT_NOTE_MAX_CHARS], so it can never grow an unbounded audit note into the
 * briefing. Empty lists are valid — they are how an in-flight workflow that predates the projection
 * degrades to empty memory without failing (AC-004).
 */
data class FeatureTaskRuntimePriorGapMemory(
  /** The audit_gap round this memory belongs to, 1-based. */
  val round: Int,
  /** "AC-###: note" lines from the audit that fired the edge. */
  val priorUnmetCriteria: List<String>,
  /** Criterion refs the subsequent implement receipt claimed to address, in order. */
  val lastImplementClaims: List<String>,
  /** Criterion refs unmet in both of the last two consecutive audits; empty when fewer than two exist. */
  val stickyIds: List<String>,
) {
  init {
    require(round >= 1) { "FeatureTaskRuntimePriorGapMemory.round must be >= 1, was $round." }
    requireListSize(priorUnmetCriteria.size, FIELD_PRIOR_UNMET_CRITERIA)
    requireListSize(lastImplementClaims.size, FIELD_LAST_IMPLEMENT_CLAIMS)
    requireListSize(stickyIds.size, FIELD_STICKY_IDS)
    priorUnmetCriteria.forEach { note ->
      require(note.isNotBlank()) { "$FIELD_PRIOR_UNMET_CRITERIA entries must be non-blank." }
      require(note.length <= FEATURE_TASK_RUNTIME_AUDIT_NOTE_MAX_CHARS) {
        "$FIELD_PRIOR_UNMET_CRITERIA entry exceeds $FEATURE_TASK_RUNTIME_AUDIT_NOTE_MAX_CHARS chars."
      }
    }
    requireNonBlankStrings(lastImplementClaims, FIELD_LAST_IMPLEMENT_CLAIMS)
    requireNonBlankStrings(stickyIds, FIELD_STICKY_IDS)
  }

  /** Emits exactly the four declared fields using the closed Text/TextList value shapes. */
  fun toProjectionFields(): List<FeatureTaskRuntimeHandoffProjectionField> = listOf(
    FeatureTaskRuntimeHandoffProjectionField(FIELD_ROUND, FeatureTaskRuntimeHandoffProjectionValue.Text(round.toString())),
    FeatureTaskRuntimeHandoffProjectionField(
      FIELD_PRIOR_UNMET_CRITERIA,
      FeatureTaskRuntimeHandoffProjectionValue.TextList(priorUnmetCriteria),
    ),
    FeatureTaskRuntimeHandoffProjectionField(
      FIELD_LAST_IMPLEMENT_CLAIMS,
      FeatureTaskRuntimeHandoffProjectionValue.TextList(lastImplementClaims),
    ),
    FeatureTaskRuntimeHandoffProjectionField(
      FIELD_STICKY_IDS,
      FeatureTaskRuntimeHandoffProjectionValue.TextList(stickyIds),
    ),
  )

  companion object {
    const val FIELD_ROUND: String = "round"
    const val FIELD_PRIOR_UNMET_CRITERIA: String = "prior_unmet_criteria"
    const val FIELD_LAST_IMPLEMENT_CLAIMS: String = "last_implement_claims"
    const val FIELD_STICKY_IDS: String = "sticky_ids"

    /** The closed allowlist the declaration and the validator read from one source. */
    val DECLARED_FIELD_NAMES: List<String> = listOf(
      FIELD_ROUND,
      FIELD_PRIOR_UNMET_CRITERIA,
      FIELD_LAST_IMPLEMENT_CLAIMS,
      FIELD_STICKY_IDS,
    )

    /** Decodes a wire map into the typed memory, reusing the same bounds as construction. */
    fun fromMap(raw: Map<String, Any?>): FeatureTaskRuntimePriorGapMemory = FeatureTaskRuntimePriorGapMemory(
      round = (raw[FIELD_ROUND] as? Number)?.toInt()
        ?: (raw[FIELD_ROUND] as? String)?.toIntOrNull()
        ?: throw IllegalArgumentException("$FIELD_ROUND must decode to an integer."),
      priorUnmetCriteria = (raw[FIELD_PRIOR_UNMET_CRITERIA] as? List<*>)?.map { it as? String }
        ?: throw IllegalArgumentException("$FIELD_PRIOR_UNMET_CRITERIA must decode to a list of strings."),
      lastImplementClaims = (raw[FIELD_LAST_IMPLEMENT_CLAIMS] as? List<*>)?.map { it as? String }
        ?: throw IllegalArgumentException("$FIELD_LAST_IMPLEMENT_CLAIMS must decode to a list of strings."),
      stickyIds = (raw[FIELD_STICKY_IDS] as? List<*>)?.map { it as? String }
        ?: throw IllegalArgumentException("$FIELD_STICKY_IDS must decode to a list of strings."),
    )
  }
}

private fun requireListSize(size: Int, field: String, max: Int = FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT) {
  require(size <= max) { "$field allows at most $max entries, had $size." }
}

private fun requireNonBlankStrings(values: List<String>, field: String) {
  requireListSize(values.size, field)
  values.forEach { value ->
    require(value.isNotBlank()) { "$field entries must be non-blank." }
    require(value.length <= FEATURE_TASK_RUNTIME_AUDIT_NOTE_MAX_CHARS) {
      "$field entry exceeds $FEATURE_TASK_RUNTIME_AUDIT_NOTE_MAX_CHARS chars."
    }
  }
}

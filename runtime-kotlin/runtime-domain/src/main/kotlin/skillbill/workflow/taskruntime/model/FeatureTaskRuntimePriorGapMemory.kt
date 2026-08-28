package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap

data class FeatureTaskRuntimePriorGapMemory(
  val round: Int,
  val priorAuditValues: List<String>,
) {
  init {
    require(round >= 1) { "FeatureTaskRuntimePriorGapMemory.round must be >= 1, was $round." }
    requireListSize(priorAuditValues.size, FIELD_PRIOR_AUDIT_VALUES)
    val maxUtf8Bytes = FeatureTaskRuntimeHandoffProjectionBudget.PLANNING_PROJECTION.maxUtf8Bytes
    var totalUtf8 = 0
    priorAuditValues.forEach { value ->
      require(value.isNotBlank()) { "$FIELD_PRIOR_AUDIT_VALUES entries must be non-blank." }
      val bytes = value.encodeToByteArray().size
      require(bytes <= maxUtf8Bytes) {
        "$FIELD_PRIOR_AUDIT_VALUES entry exceeds $maxUtf8Bytes UTF-8 bytes."
      }
      totalUtf8 += bytes
    }
    require(totalUtf8 <= maxUtf8Bytes) {
      "$FIELD_PRIOR_AUDIT_VALUES exceeds $maxUtf8Bytes UTF-8 bytes, was $totalUtf8."
    }
  }

  fun toProjectionFields(): List<FeatureTaskRuntimeHandoffProjectionField> = listOf(
    FeatureTaskRuntimeHandoffProjectionField(
      FIELD_ROUND,
      FeatureTaskRuntimeHandoffProjectionValue.Text(round.toString()),
    ),
    FeatureTaskRuntimeHandoffProjectionField(
      FIELD_PRIOR_AUDIT_VALUES,
      FeatureTaskRuntimeHandoffProjectionValue.TextList(priorAuditValues),
    ),
  )

  companion object {
    const val FIELD_ROUND: String = "round"
    const val FIELD_PRIOR_AUDIT_VALUES: String = "prior_audit_values"

    val DECLARED_FIELD_NAMES: List<String> = listOf(
      FIELD_ROUND,
      FIELD_PRIOR_AUDIT_VALUES,
    )

    @OpenBoundaryMap("Feature-task-runtime prior-gap memory decode from the handoff projection wire map")
    fun fromMap(raw: Map<String, Any?>): FeatureTaskRuntimePriorGapMemory = FeatureTaskRuntimePriorGapMemory(
      round = (raw[FIELD_ROUND] as? Number)?.toInt()
        ?: (raw[FIELD_ROUND] as? String)?.toIntOrNull()
        ?: throw IllegalArgumentException("$FIELD_ROUND must decode to an integer."),
      priorAuditValues = (raw[FIELD_PRIOR_AUDIT_VALUES] as? List<*>)?.map {
        it as? String ?: throw IllegalArgumentException("$FIELD_PRIOR_AUDIT_VALUES entries must be strings.")
      } ?: throw IllegalArgumentException("$FIELD_PRIOR_AUDIT_VALUES must decode to a list of strings."),
    )
  }
}

data class BoundedPriorGapNotes(
  val values: List<String>,
  val droppedForListCap: Int,
  val droppedForUtf8Budget: Int,
)

fun boundPriorGapNotes(
  values: List<String>,
  maxUtf8Bytes: Int = FeatureTaskRuntimeHandoffProjectionBudget.PLANNING_PROJECTION.maxUtf8Bytes,
  maxItems: Int = FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT,
): BoundedPriorGapNotes {
  val listCapped = if (values.size > maxItems) values.takeLast(maxItems) else values
  val droppedForListCap = (values.size - listCapped.size).coerceAtLeast(0)
  val keptNewestFirst = ArrayList<String>()
  var used = 0
  var droppedForUtf8Budget = 0
  for (value in listCapped.asReversed()) {
    val bytes = value.encodeToByteArray().size
    if (bytes > maxUtf8Bytes || used + bytes > maxUtf8Bytes) {
      droppedForUtf8Budget += 1
      continue
    }
    keptNewestFirst.add(value)
    used += bytes
  }
  return BoundedPriorGapNotes(
    values = keptNewestFirst.asReversed(),
    droppedForListCap = droppedForListCap,
    droppedForUtf8Budget = droppedForUtf8Budget,
  )
}

private fun requireListSize(size: Int, field: String, max: Int = FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT) {
  require(size <= max) { "$field allows at most $max entries, had $size." }
}

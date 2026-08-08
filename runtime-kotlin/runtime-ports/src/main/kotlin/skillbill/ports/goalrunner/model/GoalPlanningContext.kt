package skillbill.ports.goalrunner.model

/**
 * One catalog entry: the heading a planning agent reads, its stable identity, and where it came from.
 * Bodies never travel with the catalog; they are resolved on demand for selected [headingId]s only.
 */
data class GoalPlanningBoundaryHeading(
  val headingId: String,
  val sourcePath: String,
  val kind: String,
  val heading: String,
)

/**
 * Child-only repository context. The parent goal projection does not retain this payload; it keeps
 * manifest metadata, the current subtask index, and terminal outcomes only.
 */
data class GoalPlanningContext(
  val boundaryCatalog: List<GoalPlanningBoundaryHeading>,
  val boundaryCatalogTruncated: Boolean,
  val validationGuidance: String,
) {
  companion object {
    const val KIND_HISTORY = "history"
    const val KIND_DECISIONS = "decisions"

    /** Catalog caps: one governed source of truth for discovery, the packet validator, and the schema. */
    const val MAX_DISCOVERY_FILE_COUNT = 32
    const val MAX_HEADINGS_PER_FILE = 64
    const val MAX_CATALOG_HEADINGS = 256
    const val MAX_HEADING_TEXT_CHARS = 200
    const val MAX_DISCOVERY_TOTAL_BYTES = 512 * 1_024L
    const val MAX_VALIDATION_GUIDANCE_BYTES = 4_096

    /** Resolved-body caps: what a heading selection may pull back into the plan-phase prompt. */
    const val MAX_SELECTED_BODIES = 24
    const val MAX_BODY_BYTES = 8_192
    const val MAX_TOTAL_BODY_BYTES = 64 * 1_024
  }
}

/** One resolved entry body, delivered only because its heading was selected. */
data class GoalPlanningBoundaryBody(
  val headingId: String,
  val sourcePath: String,
  val heading: String,
  val body: String,
)

/**
 * The bounded result of resolving a heading selection. Unknown, stale, or excluded ids are reported
 * in [unresolvedHeadingIds] rather than substituted with a neighbouring entry's body.
 */
data class GoalPlanningResolvedBoundaryBodies(
  val bodies: List<GoalPlanningBoundaryBody> = emptyList(),
  val unresolvedHeadingIds: List<String> = emptyList(),
  val truncated: Boolean = false,
)

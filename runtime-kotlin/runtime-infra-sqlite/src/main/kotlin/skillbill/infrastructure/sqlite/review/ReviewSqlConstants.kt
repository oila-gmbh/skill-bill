package skillbill.infrastructure.sqlite.review

const val PARAM_ONE: Int = 1
const val PARAM_TWO: Int = 2
const val PARAM_THREE: Int = 3
const val PARAM_FOUR: Int = 4
const val PARAM_FIVE: Int = 5
const val PARAM_SIX: Int = 6
const val PARAM_SEVEN: Int = 7
const val PARAM_EIGHT: Int = 8
const val PARAM_NINE: Int = 9
const val PARAM_TEN: Int = 10
const val PARAM_ELEVEN: Int = 11
const val PARAM_TWELVE: Int = 12
const val PARAM_THIRTEEN: Int = 13
const val PARAM_FOURTEEN: Int = 14

val reviewSummarySql =
  """
  SELECT
    review_run_id,
    review_session_id,
    routed_skill,
    detected_scope,
    detected_stack,
    execution_mode,
    routed_skill_canonical,
    detected_stack_canonical,
    detected_scope_canonical,
    detected_scope_detail,
    specialist_reviews,
    review_finished_at,
    review_finished_event_emitted_at,
    orchestrated_run
  FROM review_runs
  WHERE review_run_id = ?
  """.trimIndent()

val importedFindingsSql =
  """
  SELECT finding_id, severity, confidence, issue_category, location, description, finding_text, lane_skill_name
  FROM findings
  WHERE review_run_id = ?
  ORDER BY finding_id
  """.trimIndent()

val reviewRunLanesSql =
  """
  SELECT
    lane_skill_name,
    pack_slug,
    area,
    depth,
    required,
    order_index,
    origin_layer_chain,
    resolution_state,
    review_disposition,
    bundle_composition_digest,
    segment_accounting_json,
    unreviewed_segment_ids,
    budget_dimension
  FROM review_run_lanes
  WHERE review_run_id = ?
  ORDER BY order_index, lane_skill_name
  """.trimIndent()

/**
 * Pack-and-area effectiveness input: one row per finding, carrying the run's canonical routed skill
 * (never the free-prose routed_skill text) plus the lane that produced it and its latest
 * disposition. A finding with no lane attribution keeps NULL lane columns here so the caller can
 * report it under an explicit unattributed bucket instead of dropping it from the join.
 */
val laneEffectivenessSql =
  """
  WITH latest_feedback AS (
    SELECT review_run_id, finding_id, MAX(id) AS latest_id
    FROM feedback_events
    GROUP BY review_run_id, finding_id
  )
  SELECT
    r.routed_skill_canonical AS routed_skill_canonical,
    COALESCE(l.pack_slug, f.lane_pack_slug) AS pack_slug,
    COALESCE(l.area, f.lane_area) AS area,
    COALESCE(fe.event_type, '') AS outcome_type
  FROM findings f
  JOIN review_runs r ON r.review_run_id = f.review_run_id
  LEFT JOIN review_run_lanes l
    ON l.review_run_id = f.review_run_id AND l.lane_skill_name = f.lane_skill_name
  LEFT JOIN latest_feedback lf
    ON lf.review_run_id = f.review_run_id AND lf.finding_id = f.finding_id
  LEFT JOIN feedback_events fe
    ON fe.id = lf.latest_id
  WHERE (? IS NULL OR f.review_run_id = ?)
  """.trimIndent()

val findingMetadataSql =
  """
  SELECT finding_id, severity, confidence
  FROM findings
  WHERE review_run_id = ? AND finding_id = ?
  """.trimIndent()

val numberedFindingsSql =
  """
  SELECT finding_id, severity, confidence, location, description
  FROM findings
  WHERE review_run_id = ?
  ORDER BY finding_id
  """.trimIndent()

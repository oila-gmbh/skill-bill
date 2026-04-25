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

val reviewSummarySql =
  """
  SELECT
    review_run_id,
    review_session_id,
    routed_skill,
    detected_scope,
    detected_stack,
    execution_mode,
    specialist_reviews,
    review_finished_at,
    review_finished_event_emitted_at,
    orchestrated_run
  FROM review_runs
  WHERE review_run_id = ?
  """.trimIndent()

val importedFindingsSql =
  """
  SELECT finding_id, severity, confidence, location, description, finding_text
  FROM findings
  WHERE review_run_id = ?
  ORDER BY finding_id
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

package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

fun FeatureTaskRuntimeRunLoopRecordRejection.scrubOffVocabularyVerdictQuote(text: String): String {
  val start = text.indexOf(OFF_VOCABULARY_VERDICT_OPEN, ignoreCase = true)
  if (start < 0) return text
  val afterOpenQuote = start + OFF_VOCABULARY_VERDICT_OPEN.length
  val closeAt = text.lastIndexOf(OFF_VOCABULARY_VERDICT_CLOSE_BOUNDARY)
  return if (closeAt >= afterOpenQuote) {
    text.substring(0, start) + "off-vocabulary verdict" + text.substring(closeAt + 1)
  } else {
    // Cap or malformation left no gate boundary — strip the open marker and remainder so a partial
    // response-derived quote cannot remain outside the repair section.
    text.substring(0, start) + "off-vocabulary verdict"
  }
}

/** Dual-reason validators sometimes append the instance dump after an em-dash or colon. */
val OFFENDING_VALUE_APPENDIX_PATTERN =
  Regex("""(?:\s*[—-]\s*)?offending value:.*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/** Audit repair gates list expected=/actual= receipt identifiers derived from the rejected output. */
val EXPECTED_ACTUAL_LIST_PATTERN =
  Regex("""\bexpected=\[[^\]]*]\s*actual=\[[^\]]*]\.?""", RegexOption.IGNORE_CASE)

/** Length caps stated by typed audit-repair reference rules (`allows at most N characters`). */
val BOUNDED_REF_LENGTH_CAP_PATTERN =
  Regex("""(?:allows|must be) at most ([0-9][0-9,]*) characters""", RegexOption.IGNORE_CASE)

val SCHEMA_DETAIL_TYPE_WORDS = setOf(
  "array",
  "boolean",
  "integer",
  "null",
  "number",
  "object",
  "string",
)

const val MIN_RESPONSE_STRING_VALUE_LENGTH = 4

val INVENTORY_EXTENDING_PHASES: Set<String> = setOf(
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
)

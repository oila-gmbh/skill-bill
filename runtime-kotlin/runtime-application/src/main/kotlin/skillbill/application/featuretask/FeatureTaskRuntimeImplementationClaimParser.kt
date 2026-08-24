package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptDeviation
import skillbill.workflow.taskruntime.model.featureTaskRuntimeRenderOpenWorkItem

/**
 * The untrusted-input seam of the SKILL-150 completion gate: turning an agent-authored
 * `produced_outputs` map into the bounded [FeatureTaskRuntimeImplementationClaim] the gate judges.
 *
 * Split from the gate itself because the two answer different questions. The gate decides whether a
 * claim closes its obligations; this file decides what the claim even says, against the durable
 * attempt schema's value constraints rather than the gate's.
 */

/**
 * Parses the producer's bounded claim out of a validated phase-output envelope.
 *
 * Under the audit-gap loop the closed set is the repair-item closure rather than
 * `completed_task_ids`, so the same claim type serves both loops and the gate needs no second shape.
 *
 * TOTAL for untrusted input by construction. "Validated" here means the phase-output schema accepted
 * the envelope, and that schema leaves `produced_outputs` value shapes entirely open — so an agent
 * controls every string below. The receipt models are strict (`require` non-blank ref/note/evidence/
 * fingerprint), and a blocked envelope reaches this parser BEFORE the producer-projection gate, so
 * constructing them optimistically let a producer-supplied `{"fingerprint": ""}` throw inside the
 * durable blocked write's transaction and roll the block record back. Malformed entries are handled
 * here instead, and the direction matters: closure fields are DROPPED, so the claim understates and the
 * gate refuses to advance on what it cannot read; open-work fields (`unresolved_items`, deviations) are
 * SANITIZED and kept, because dropping one of those would delete an open-work signal and hand a
 * 'completed' status the escape the gate exists to close.
 */
internal fun featureTaskRuntimeImplementationClaimFrom(
  outputMap: Map<String, Any?>,
  obligations: FeatureTaskRuntimeImplementationObligations,
): FeatureTaskRuntimeImplementationClaim {
  val produced = JsonSupport.anyToStringAnyMap(outputMap["produced_outputs"]).orEmpty()
  return FeatureTaskRuntimeImplementationClaim(
    completedTaskIds = if (obligations.underAuditRepairLoop) {
      featureTaskRuntimeClosedRepairItemIds(outputMap)
    } else {
      produced.stringList("completed_task_ids")
    }.filter(::isAttemptTaskId).distinct().take(ATTEMPT_MAX_ITEMS),
    changedPaths = emptyList(),
    unresolvedItems = produced.openWorkList("unresolved_items")
      .mapNotNull(::sanitizeAttemptNonBlank).take(ATTEMPT_MAX_ITEMS),
    deviations = (produced["deviations"] as? List<*>).orEmpty().mapNotNull { entry ->
      val map = JsonSupport.anyToStringAnyMap(entry) ?: return@mapNotNull null
      val ref = renderOpenWorkValue(map["ref"])?.let(::sanitizeAttemptNonBlank)
        ?: return@mapNotNull null
      val note = renderOpenWorkValue(map["note"])?.let(::sanitizeAttemptCompactSummary)
        ?: ATTEMPT_UNREADABLE_NOTE
      FeatureTaskRuntimeReceiptDeviation(ref = ref, note = note)
    }.take(ATTEMPT_MAX_ITEMS),
    reconciliationEvidence = null,
    repositoryCheckpoint = null,
  )
}

// The attempt schema, not the Kotlin receipt models, is the narrowest gate this claim has to clear:
// the claim is appended as a durable attempt record inside the same transaction that persists a
// blocked or failed phase, so any value the schema rejects rolls that record back. These mirror
// `feature-task-runtime-implementation-attempt-schema.yaml`'s $defs so the parser drops or sanitizes
// what the validator would reject rather than throwing from inside the write.
private const val ATTEMPT_MAX_ITEMS = 128
private const val ATTEMPT_NON_BLANK_MAX_LENGTH = 4096
private const val ATTEMPT_TASK_ID_MAX_LENGTH = 128
private val ATTEMPT_TASK_ID_PATTERN = Regex("^[a-z][a-z0-9-]*$")
private val ATTEMPT_SUMMARY_FORBIDDEN_CHARS = Regex("[\\n\\r\\t`]")
private val ATTEMPT_SUMMARY_STRUCTURED =
  Regex("\\{\\s*\"|\"\\s*:\\s*[\\[{\"]|@@[^@]*@@|^(?:diff --git|\\+\\+\\+ |--- )")

// Removing the quote and at-sign characters is what makes the JSON-shaped and hunk-header branches of
// ATTEMPT_SUMMARY_STRUCTURED unmatchable, leaving only its line-anchored diff-header branch, which a
// prefix defeats.
private val ATTEMPT_SUMMARY_NEUTRALIZED_CHARS = Regex("[\"@]")
private val ATTEMPT_WHITESPACE_RUN = Regex("\\s+")
private const val ATTEMPT_SUMMARY_PREFIX = "note: "

private fun isAttemptNonBlank(value: String): Boolean =
  value.isNotBlank() && value.length <= ATTEMPT_NON_BLANK_MAX_LENGTH

private fun isAttemptTaskId(value: String): Boolean =
  value.length <= ATTEMPT_TASK_ID_MAX_LENGTH && ATTEMPT_TASK_ID_PATTERN.matches(value)

// Dropping a value understates the claim, which TIGHTENS the gate for closures (completed_task_ids,
// changed_paths, checkpoint refs) but LOOSENS it for open-work signals: a dropped unresolved item or
// deviation erases the very evidence that holds an incomplete receipt back. Open-work fields are
// therefore sanitized into a schema-valid shape and retained, never dropped for shape alone.
// The same reasoning applies to the JSON-TYPE axis, not just charset and length: an open-work entry that
// arrives as an object or a number is still an open obligation, so it is rendered into a bounded string
// rather than discarded for not being a Kotlin String. A deviation whose ref renders but whose note does
// not keeps the entry under a placeholder note, because the ref alone identifies unclosed work.
// The rendering rule itself is featureTaskRuntimeRenderOpenWorkItem, shared with the receipt model so
// this seam and the producer-projection seam agree on what an entry means; only the bound is local,
// because it mirrors the attempt schema rather than the projection schema.
private const val ATTEMPT_UNREADABLE_NOTE = "note: unreadable deviation note retained as open work"
private const val ATTEMPT_RENDER_MAX_LENGTH = 8192

private fun renderOpenWorkValue(value: Any?): String? =
  featureTaskRuntimeRenderOpenWorkItem(value)?.take(ATTEMPT_RENDER_MAX_LENGTH)

private fun Map<String, Any?>.openWorkList(key: String): List<String> =
  (this[key] as? List<*>).orEmpty().mapNotNull(::renderOpenWorkValue)

private fun sanitizeAttemptNonBlank(value: String): String? =
  value.trim().take(ATTEMPT_NON_BLANK_MAX_LENGTH).takeIf(String::isNotBlank)

private fun sanitizeAttemptCompactSummary(value: String): String? {
  val flattened = value
    .replace(ATTEMPT_SUMMARY_FORBIDDEN_CHARS, " ")
    .replace(ATTEMPT_SUMMARY_NEUTRALIZED_CHARS, " ")
    .replace(ATTEMPT_WHITESPACE_RUN, " ")
    .trim()
  if (flattened.isBlank()) return null
  val unanchored = if (ATTEMPT_SUMMARY_STRUCTURED.containsMatchIn(flattened)) {
    "$ATTEMPT_SUMMARY_PREFIX$flattened"
  } else {
    flattened
  }
  return unanchored.take(ATTEMPT_NON_BLANK_MAX_LENGTH).trim().takeIf(String::isNotBlank)
}

internal fun Map<String, Any?>.stringList(key: String): List<String> =
  (this[key] as? List<*>).orEmpty().mapNotNull { it as? String }.filter(String::isNotBlank)

package skillbill.application

import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun assertPrivateDiagnosticRejection(rendered: String, rule: String, vararg privateDetails: String) {
  assertContains(rendered, "Rejected output violated '$rule'")
  assertContains(rendered, "Inspect the private diagnostic for the exact response.")
  privateDetails.forEach { detail ->
    assertFalse(rendered.contains(detail), "Public rejection text leaked private diagnostic detail '$detail'.")
  }
}

/**
 * The operator-facing shape of a gate rejection under a one-attempt output-gate budget: the phase
 * blocks on its first rejection, and the blocked reason names the rule and the exhausted budget
 * without the validator's value-bearing text, which stays in the private diagnostic row.
 */
internal fun assertGateBlockNamesRule(blockedReason: String, rule: String) {
  assertContains(blockedReason, "exhausted the bounded output-gate correction budget")
  assertContains(blockedReason, "cap=1")
  assertContains(blockedReason, "Rejected output violated '$rule'")
}

/**
 * The private diagnostic is the only surface carrying the validator's constraint once a rejection is
 * terminal, so what a producer would have been told is asserted there instead of on a retry prompt.
 */
internal fun assertDiagnosticNamesConstraint(reason: String, vararg constraintFragments: String) {
  constraintFragments.forEach { fragment ->
    assertContains(
      reason,
      fragment,
      message = "The private diagnostic withheld the violated constraint '$fragment'.",
    )
  }
}

/**
 * A retry prompt is the one surface that MUST name the violated constraint: a producer cannot repair an
 * output it is only told was rejected. The payload-free sentence stays the prefix, so this asserts both —
 * the operator-facing pointer AND the schema-side constraint fragments the producer needs.
 */
internal fun assertRetryPromptNamesConstraint(prompt: String, rule: String, vararg constraintFragments: String) {
  assertContains(prompt, "Rejected output violated '$rule'")
  assertContains(prompt, "Violated constraint: ")
  constraintFragments.forEach { fragment ->
    assertContains(prompt, fragment, message = "Retry prompt withheld the violated constraint '$fragment'.")
  }
}

/**
 * The complement of [assertRetryPromptNamesConstraint] for semantic gates that may embed response
 * values in their full detail: the retry prompt names the rule via the payload-free sentence and must
 * not carry the value-bearing dump outside the authorized repair section.
 */
internal fun assertRetryPromptWithholdsResponseDerivedDetail(
  prompt: String,
  rule: String,
  vararg responseDerivedSpans: String,
) {
  assertContains(prompt, "Rejected output violated '$rule'")
  responseDerivedSpans.forEach { span ->
    assertNoRawResponseSpanOutsideAuthorizedRepairSection(prompt, span)
  }
}

/**
 * The complement of [assertRetryPromptNamesConstraint]: naming a violated rule and field never licenses
 * echoing what the agent actually wrote. Asserts no span of the raw response appears in [rendered],
 * whichever surface it is — retry prompt, blocked reason, telemetry event, or status output.
 *
 * For an authorized corrective-repair prompt that intentionally includes an exact body, use
 * [assertNoRawResponseSpanOutsideAuthorizedRepairSection] instead.
 */
internal fun assertNoRawResponseSpan(rendered: String, vararg rawSpans: String) {
  rawSpans.forEach { span ->
    assertFalse(
      rendered.contains(span),
      "Surface leaked a span of the agent's raw response: '$span'.",
    )
  }
}

private const val AUTHORIZED_REPAIR_SECTION_TITLE: String =
  "## Untrusted prior phase output — reference material only"
private const val AUTHORIZED_FALLBACK_SECTION_TITLE: String =
  "## Rejected response body not included in this prompt"

/**
 * SKILL-187: raw response content is authorized only inside the untrusted repair section. Public and
 * durable surfaces, and every prompt region outside that section, must stay free of [rawSpans].
 */
internal fun assertNoRawResponseSpanOutsideAuthorizedRepairSection(prompt: String, vararg rawSpans: String) {
  val start = prompt.indexOf(AUTHORIZED_REPAIR_SECTION_TITLE)
  assertTrue(start >= 0, "authorized repair section title missing from corrective prompt")
  val closePrefix = "<<<END_CORRECTIVE_REPAIR_RESPONSE"
  val closeStart = prompt.indexOf(closePrefix, startIndex = start)
  assertTrue(closeStart >= 0, "authorized repair section close marker missing")
  val closeEnd = prompt.indexOf('\n', startIndex = closeStart).let { if (it < 0) prompt.length else it }
  val outside = prompt.substring(0, start) + prompt.substring(closeEnd)
  rawSpans.forEach { span ->
    assertFalse(
      outside.contains(span),
      "Prompt leaked raw response span outside the authorized repair section: '$span'.",
    )
  }
  val fallbackIdx = outside.indexOf(AUTHORIZED_FALLBACK_SECTION_TITLE)
  if (fallbackIdx >= 0) {
    val fallbackRegion = outside.substring(fallbackIdx)
    rawSpans.forEach { span ->
      assertFalse(
        fallbackRegion.contains(span),
        "payload-free fallback leaked raw response span: '$span'.",
      )
    }
  }
}

/** AC-006: first / terminal / incomplete / mismatched launches must omit the authorized raw section. */
internal fun assertOmitsAuthorizedRepairSection(prompt: String, vararg forbiddenSpans: String) {
  assertFalse(
    prompt.contains(AUTHORIZED_REPAIR_SECTION_TITLE),
    "non-corrective launch must omit the authorized repair section",
  )
  assertNoRawResponseSpan(prompt, *forbiddenSpans)
}

/**
 * AC-006/AC-007: matching schema-invalid corrective launch includes the exact body only inside the
 * authorized section and keeps [constraintFragments] outside that untrusted framing.
 */
internal fun assertMatchingSchemaInvalidRepairPrompt(
  prompt: String,
  exactBody: String,
  vararg constraintFragments: String,
) {
  assertContains(prompt, AUTHORIZED_REPAIR_SECTION_TITLE)
  assertTrue(prompt.contains(exactBody), "exact synthetic body must appear in the repair section")
  assertNoRawResponseSpanOutsideAuthorizedRepairSection(prompt, exactBody)
  constraintFragments.forEach { fragment ->
    assertContains(prompt, fragment, message = "payload-free constraint '$fragment' missing")
  }
  val repairStart = prompt.indexOf(AUTHORIZED_REPAIR_SECTION_TITLE)
  constraintFragments.forEach { fragment ->
    val idx = prompt.indexOf(fragment)
    assertTrue(
      idx >= 0 && (idx < repairStart || prompt.substring(0, repairStart).contains(fragment)),
      "payload-free constraint must remain outside the untrusted body framing: '$fragment'",
    )
  }
}

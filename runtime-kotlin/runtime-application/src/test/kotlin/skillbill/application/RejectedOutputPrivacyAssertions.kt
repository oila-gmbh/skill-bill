package skillbill.application

import kotlin.test.assertContains
import kotlin.test.assertFalse

internal fun assertPrivateDiagnosticRejection(rendered: String, rule: String, vararg privateDetails: String) {
  assertContains(rendered, "Rejected output violated '$rule'")
  assertContains(rendered, "Inspect the private diagnostic for the exact response.")
  privateDetails.forEach { detail ->
    assertFalse(rendered.contains(detail), "Public rejection text leaked private diagnostic detail '$detail'.")
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
 * The complement of [assertRetryPromptNamesConstraint]: naming a violated rule and field never licenses
 * echoing what the agent actually wrote. Asserts no span of the raw response appears in [rendered],
 * whichever surface it is — retry prompt, blocked reason, telemetry event, or status output.
 */
internal fun assertNoRawResponseSpan(rendered: String, vararg rawSpans: String) {
  rawSpans.forEach { span ->
    assertFalse(
      rendered.contains(span),
      "Surface leaked a span of the agent's raw response: '$span'.",
    )
  }
}

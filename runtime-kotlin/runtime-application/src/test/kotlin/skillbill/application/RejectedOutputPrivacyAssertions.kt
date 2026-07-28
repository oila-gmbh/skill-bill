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

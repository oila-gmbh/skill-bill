package skillbill.application.featuretask.validation.model

import skillbill.ports.validation.model.ValidationGateFinding
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidationGateTriagePredicateTest {
  @Test
  fun `requiresUnparseableGateTriage is true only for sole unparseable_gate_failure`() {
    val unparseable = ValidationGateFinding(
      module = "<validation-gate>",
      ruleOrTestId = UNPARSEABLE_GATE_FAILURE_RULE_ID,
      message = "blob",
      location = null,
    )
    assertTrue(requiresUnparseableGateTriage(listOf(unparseable)))
    assertFalse(
      requiresUnparseableGateTriage(
        listOf(
          unparseable,
          ValidationGateFinding("m", "kotlin_compiler", "broken", "Foo.kt"),
        ),
      ),
    )
    assertFalse(requiresUnparseableGateTriage(listOf(ValidationGateFinding("m", "kotlin_compiler", "broken", "Foo.kt"))))
  }
}

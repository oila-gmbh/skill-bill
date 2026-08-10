package skillbill.application.featuretask.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SuppressionJustificationGateTest {
  private val intro = IntroducedSuppression("src/Foo.kt", "@Suppress", 1)

  @Test
  fun `ungated or zero delta allows without justification`() {
    assertIs<SuppressionGateDecision.Allow>(
      SuppressionJustificationGate.evaluate(
        SuppressionDelta(gated = false, introductions = emptyList()),
        emptyList(),
      ),
    )
    assertIs<SuppressionGateDecision.Allow>(
      SuppressionJustificationGate.evaluate(
        SuppressionDelta(gated = true, introductions = emptyList()),
        emptyList(),
      ),
    )
  }

  @Test
  fun `absent justification blocks with path and marker names`() {
    val blocked = assertIs<SuppressionGateDecision.Block>(
      SuppressionJustificationGate.evaluate(
        SuppressionDelta(gated = true, introductions = listOf(intro)),
        emptyList(),
      ),
    )
    assertTrue(blocked.reason.contains("src/Foo.kt"))
    assertTrue(blocked.reason.contains("@Suppress"))
    assertTrue(blocked.reason.contains("require justification"))
  }

  @Test
  fun `under-reported justification blocks`() {
    val blocked = assertIs<SuppressionGateDecision.Block>(
      SuppressionJustificationGate.evaluate(
        SuppressionDelta(
          gated = true,
          introductions = listOf(
            intro,
            IntroducedSuppression("src/Bar.kt", "@Suppress", 1),
          ),
        ),
        listOf(
          SuppressionJustification(
            path = "src/Foo.kt",
            silencedRuleOrCheck = "UnusedPrivateMember",
            rationale = "Legacy API boundary prevents a root-cause fix this run.",
          ),
        ),
      ),
    )
    assertTrue(blocked.reason.contains("under-reports"))
    assertTrue(blocked.reason.contains("src/Bar.kt"))
  }

  @Test
  fun `fully accounted justification allows and retains entries`() {
    val justification = SuppressionJustification(
      path = "src/Foo.kt",
      silencedRuleOrCheck = "UnusedPrivateMember",
      rationale = "Third-party callback signature forces the silence.",
    )
    val allowed = assertIs<SuppressionGateDecision.Allow>(
      SuppressionJustificationGate.evaluate(
        SuppressionDelta(gated = true, introductions = listOf(intro)),
        listOf(justification),
      ),
    )
    assertEquals(listOf(justification), allowed.justifications)
  }

  @Test
  fun `oversized rationale is rejected at parse`() {
    val parsed = SuppressionJustification.parseAll(
      listOf(
        mapOf(
          "path" to "a.kt",
          "silenced_rule_or_check" to "X",
          "rationale" to "x".repeat(SuppressionJustification.MAX_RATIONALE_CHARS + 1),
        ),
      ),
    )
    assertIs<SuppressionJustification.ParseResult.Invalid>(parsed)
  }

  @Test
  fun `raw-output shaped rationale is rejected at parse`() {
    val parsed = SuppressionJustification.parseAll(
      listOf(
        mapOf(
          "path" to "a.kt",
          "silenced_rule_or_check" to "X",
          "rationale" to "BUILD SUCCESSFUL in 12s\n".repeat(3),
        ),
      ),
    )
    assertIs<SuppressionJustification.ParseResult.Invalid>(parsed)
  }
}

package skillbill.application.featuretask.validation

import skillbill.ports.workflow.model.WorkflowScopedPathContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuppressionDeltaMeasurerTest {
  @Test
  fun `no declared markers short-circuits ungated`() {
    val delta = SuppressionDeltaMeasurer.measure(
      markers = emptyList(),
      pathContents = listOf(
        WorkflowScopedPathContent("a.kt", "a.kt", "@Suppress(\"X\")", null),
      ),
    )
    assertFalse(delta.gated)
    assertEquals(0, delta.totalIntroduced)
  }

  @Test
  fun `clean path yields zero delta`() {
    val content = "fun ok() = 1\n"
    val delta = SuppressionDeltaMeasurer.measure(
      markers = listOf("@Suppress"),
      pathContents = listOf(
        WorkflowScopedPathContent("a.kt", "a.kt", content, content),
      ),
    )
    assertTrue(delta.gated)
    assertEquals(0, delta.totalIntroduced)
  }

  @Test
  fun `base-existing suppressions are not introduced`() {
    val base = "@Suppress(\"UNUSED\")\nfun f() = 1\n"
    val head = "@Suppress(\"UNUSED\")\nfun f() = 2\n"
    val delta = SuppressionDeltaMeasurer.measure(
      markers = listOf("@Suppress"),
      pathContents = listOf(WorkflowScopedPathContent("a.kt", "a.kt", head, base)),
    )
    assertEquals(0, delta.totalIntroduced)
  }

  @Test
  fun `rename keeps base identity and does not invent introductions`() {
    val base = "@Suppress(\"UNUSED\")\nfun f() = 1\n"
    val head = "@Suppress(\"UNUSED\")\nfun f() = 1\n"
    val delta = SuppressionDeltaMeasurer.measure(
      markers = listOf("@Suppress"),
      pathContents = listOf(
        WorkflowScopedPathContent(
          headPath = "new/A.kt",
          basePath = "old/A.kt",
          headContent = head,
          baseContent = base,
        ),
      ),
    )
    assertEquals(0, delta.totalIntroduced)
  }

  @Test
  fun `newly introduced marker counts toward delta`() {
    val delta = SuppressionDeltaMeasurer.measure(
      markers = listOf("@Suppress", "@file:Suppress"),
      pathContents = listOf(
        WorkflowScopedPathContent(
          headPath = "a.kt",
          basePath = "a.kt",
          headContent = "@file:Suppress(\"all\")\n@Suppress(\"X\")\nfun f()=1\n",
          baseContent = "fun f()=1\n",
        ),
      ),
    )
    assertEquals(2, delta.totalIntroduced)
    assertEquals(setOf("@Suppress", "@file:Suppress"), delta.introductions.map { it.marker }.toSet())
  }

  @Test
  fun `measurer ignores agent self-report shaped fields when only path contents are supplied`() {
    // Fixtures deliberately omit any agent-emitted validation_result; measurement inputs are
    // exclusively the scoped path contents the caller already resolved from git/pack evidence.
    val delta = SuppressionDeltaMeasurer.measure(
      markers = listOf("@Suppress"),
      pathContents = listOf(
        WorkflowScopedPathContent("a.kt", "a.kt", "fun f()=1\n", "fun f()=1\n"),
      ),
    )
    assertEquals(0, delta.totalIntroduced)
  }
}

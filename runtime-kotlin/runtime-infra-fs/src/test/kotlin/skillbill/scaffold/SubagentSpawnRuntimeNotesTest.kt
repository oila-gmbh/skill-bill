@file:Suppress("MaxLineLength", "ktlint:standard:max-line-length")

package skillbill.scaffold

import skillbill.scaffold.rendering.renderSubagentSpawnRuntimeNotes
import skillbill.scaffold.runtime.RepoValidationRuntime
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubagentSpawnRuntimeNotesTest {
  /**
   * The rendered Cursor paragraph restates launch rules that `review-delegation/PLAYBOOK.md` owns,
   * and the renderer cannot read that playbook. Without this pin, relaxing a rule in one place ships
   * an installed orchestrator that launches lanes by a rule the governed contract no longer states —
   * and the wrapper is generated, so a reviewer never sees the two side by side.
   */
  @Test
  fun `rendered cursor spawn guidance restates every governed cursor launch rule`() {
    val playbook = Files.readString(repoRootFromTest().resolve("orchestration/review-delegation/PLAYBOOK.md"))
    val cursorSection = RepoValidationRuntime.extractH2(playbook, "Cursor")
    assertTrue(cursorSection.isNotBlank(), "review-delegation/PLAYBOOK.md must keep a `## Cursor` section")

    val rendered = renderSubagentSpawnRuntimeNotes("bill-parity-orchestrator", listOf("parity-arch"))

    GOVERNED_CURSOR_LAUNCH_RULES.forEach { rule ->
      assertTrue(rule in cursorSection, "review-delegation/PLAYBOOK.md `## Cursor` no longer states: $rule")
      assertTrue(rule in rendered, "Rendered Cursor spawn paragraph no longer states: $rule")
    }
  }

  // An empty list means no `native-agents/` entry parsed. Rendering the notes anyway interpolates
  // `specialists.first()` into the per-runtime paragraphs and fails the whole authoring render.
  @Test
  fun `no specialists renders no spawn notes`() {
    assertEquals("", renderSubagentSpawnRuntimeNotes("bill-parity-orchestrator", emptyList()))
  }

  private companion object {
    /** Verbatim in both the governed playbook section and the rendered paragraph. */
    val GOVERNED_CURSOR_LAUNCH_RULES = listOf(
      "project scope wins on a name conflict",
      "so they launch in parallel, not one-at-a-time",
      "The installed native agent's embedded governed rubric is authoritative",
      "Cursor lane identity is the routed area plus the assignment digest from the launch plan",
      "is a failed lane",
      "an inline review and must be reported as such",
      "built-in types such as `generalPurpose`",
      "no installed agent matching a selected lane",
      "delegated review is required for this scope but unavailable here",
      "Do not silently downgrade to inline, substitute a built-in worker, or claim delegated coverage from the parent context.",
    )
  }
}

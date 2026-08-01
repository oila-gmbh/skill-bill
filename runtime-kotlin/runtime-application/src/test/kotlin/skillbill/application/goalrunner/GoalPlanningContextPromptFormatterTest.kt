package skillbill.application.goalrunner

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class GoalPlanningContextPromptFormatterTest {
  @Test
  fun `planning payload is child-only and parent retention is terminal-only`() {
    val prompt = GoalPlanningContextPromptFormatter.append(
      prompt = "Plan this child.",
      packet = mapOf(
        "parent_spec" to "parent planning payload",
        "implementation_summary" to "child implementation detail",
        "audit" to "child audit detail",
        "review" to "child review detail",
        "diagnostics" to "child diagnostics",
        "raw_child_output" to "child stdout",
      ),
      subtask = null,
      phaseId = "preplan",
    )

    assertContains(prompt, "This is child-only planning context.")
    assertContains(
      prompt,
      "Do not copy its payload, implementation summary, audit, review, diagnostic, or raw child output",
    )
    assertContains(
      prompt,
      "The parent retains manifest metadata, the current subtask index, and terminal outcomes only",
    )
    assertContains(prompt, "{status, commit_sha, workflow_id}")
    assertFalse(prompt.contains("parent projection payload"))
  }
}

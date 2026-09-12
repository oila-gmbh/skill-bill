package skillbill.engine.goalrunner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.nio.file.Path

class GoalRunnerPullRequestFormattingTest {
  @Test
  fun `pull request uses issue key title case feature title and capmo summary sections`() {
    val request = manifest().toPullRequestRequest(Path.of("/repo"))

    assertEquals("[333] Review Citation Ingestion", request.title)
    assertTrue(request.body.startsWith("# Summary\n\n"))
    assertTrue(request.body.contains("This pull request delivers Review Citation Ingestion"))
    assertTrue(request.body.contains("## Feature Flags\n\nN/A"))
    assertTrue(request.body.contains("## Media\n\nN/A"))
    assertTrue(request.body.contains("# How Has This Been Tested?"))
    assertTrue(request.body.contains("- Parse citation metadata"))
    assertFalse(request.body.contains("# Checklist"))
    assertFalse(request.body.contains("- [ ]"))
  }

  private fun manifest() = DecompositionManifest(
    issueKey = "333",
    featureName = "review-citation-ingestion",
    parentSpecPath = ".feature-specs/333-review-citation-ingestion/spec.md",
    baseBranch = "main",
    featureBranch = "feat/333-review-citation-ingestion",
    currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "start"),
    subtasks = listOf(
      DecompositionSubtask(
        id = 1,
        name = "Parse citation metadata",
        specPath = ".feature-specs/333-review-citation-ingestion/spec_subtask_1.md",
      ),
    ),
  )
}

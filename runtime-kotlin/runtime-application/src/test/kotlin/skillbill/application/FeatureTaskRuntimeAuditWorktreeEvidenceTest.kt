package skillbill.application

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class FeatureTaskRuntimeAuditWorktreeEvidenceTest {
  @Test
  fun `audit task and briefing require uncommitted repairs instead of only the head commit`() {
    val briefing = promptComposerBriefingFor("audit")
    val prompt = composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, briefing)
    val task = prompt.substringAfter("Task: ").substringBefore("\n")

    listOf(task, briefing.briefingText).forEach { evidenceInstructions ->
      assertContains(evidenceInstructions, "current working-tree contents")
      assertContains(evidenceInstructions, "staged, unstaged, and untracked")
      assertContains(evidenceInstructions, "head_ref is the last committed revision")
      assertContains(evidenceInstructions, "Read the current files at scoped_owned_paths")
      assertContains(evidenceInstructions, "git diff <base_ref> -- <scoped paths>")
      assertContains(evidenceInstructions, "read owned untracked files directly")
      assertContains(evidenceInstructions, "git show <head_ref>:<path> alone is not current-state evidence")
      assertFalse(evidenceInstructions.contains("the diff over base_ref/head_ref plus"))
    }
  }
}

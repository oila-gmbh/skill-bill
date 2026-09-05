package skillbill.application

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseSettlementTarget
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimePhasePromptComposerSettlementTest {

  @Test
  fun `prose phases with a settlement target are told to settle through the MCP tools`() {
    val target = FeatureTaskRuntimePhaseSettlementTarget(workflowId = "wftr-20260904-210526-r3x0", attempt = 2)
    listOf("preplan", "plan", "implement").forEach { phaseId ->
      val prompt = composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor(phaseId)) {
        copy(phaseSettlement = target)
      }

      assertContains(prompt, "## Required final output (durable settlement)", false, "settlement heading for $phaseId")
      assertContains(prompt, "mcp__skill-bill__feature_task_phase_complete", false, "complete tool for $phaseId")
      assertContains(prompt, "mcp__skill-bill__feature_task_phase_block", false, "block tool for $phaseId")
      assertContains(prompt, "workflow_id \"wftr-20260904-210526-r3x0\"", false, "pinned workflow id for $phaseId")
      assertContains(prompt, "phase_id \"$phaseId\", attempt 2", false, "pinned attempt for $phaseId")
      assertContains(prompt, "## Fallback final output (validated schema gate", false, "envelope demoted for $phaseId")
      assertFalse(
        prompt.contains("## Required final output (validated schema gate)"),
        "no required envelope for $phaseId",
      )
      assertTrue(
        prompt.indexOf("## Required final output (durable settlement)") <
          prompt.indexOf("## Fallback final output (validated schema gate"),
        "settlement precedes the fallback envelope for $phaseId",
      )
    }
  }

  @Test
  fun `non-prose phases keep the printed envelope contract even with a settlement target`() {
    val target = FeatureTaskRuntimePhaseSettlementTarget(workflowId = "wftr-20260904-210526-r3x0", attempt = 1)
    listOf("review", "audit", "validate").forEach { phaseId ->
      val prompt = composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor(phaseId)) {
        copy(phaseSettlement = target)
      }

      assertFalse(prompt.contains("durable settlement"), "no settlement directive for $phaseId")
      assertContains(
        prompt,
        "## Required final output (validated schema gate)",
        false,
        "required envelope for $phaseId",
      )
    }
  }
}

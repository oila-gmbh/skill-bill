package skillbill.application

import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelectionEntry
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.application.featuretask.FeatureTaskRuntimePhasePromptComposer
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionBudget
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureTaskRuntimeAddonBudgetTest {
  @Test
  fun `hydrated add-on content reaches every phase of the declared feature-task consumer`() {
    val selection = selection("small content")

    // `feature_addon_usage.feature-task` scopes add-ons to the run, not to a phase subset, so every
    // phase of the run is the declared consumer.
    listOf("preplan", "plan", "implement", "audit", "review", "validate", "write_history", "commit_push", "pr")
      .forEach { phaseId ->
        assertEquals(
          selection,
          FeatureTaskRuntimePhasePromptComposer.budgetedAddonsFor(phaseId, selection),
          "phase '$phaseId' is part of the declared feature-task consumer but lost its add-on content",
        )
      }
  }

  @Test
  fun `oversized add-on content is rejected independently of the phase-receipt budget`() {
    val receiptBudget = FeatureTaskRuntimeHandoffProjectionBudget.PHASE_RECEIPT.maxUtf8Bytes
    val addonBudget = FeatureTaskRuntimeHandoffProjectionBudget.ADDON_CONTENT.maxUtf8Bytes
    // Sits above the add-on budget but still well under the phase-receipt budget, so a rejection
    // here can only come from the add-on budget: neither budget borrows the other's headroom.
    val oversized = "a".repeat(addonBudget + 1)
    assertTrue(oversized.length < receiptBudget, "the fixture must not also exceed the phase-receipt budget")

    val error = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      FeatureTaskRuntimePhasePromptComposer.budgetedAddonsFor("implement", selection(oversized))
    }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.BUDGET_OVERFLOW, error.failureKind)
    assertEquals(FeatureTaskRuntimePhasePromptComposer.ADDON_CONTENT_PROJECTION_NAME, error.projectionName)
    assertContains(error.message.orEmpty(), "$addonBudget-byte")
    assertTrue(addonBudget != receiptBudget, "the add-on and phase-receipt budgets must be independent")
  }

  private fun selection(content: String) = HydratedAgentAddonSelection(
    entries = listOf(
      HydratedAgentAddonSelectionEntry(
        persisted = PersistedAgentAddonSelectionEntry(
          slug = "acme-addon",
          sourceIdentity = "local:acme",
          contentSha256 = "0".repeat(64),
        ),
        description = "acme add-on",
        content = content,
      ),
    ),
  )
}

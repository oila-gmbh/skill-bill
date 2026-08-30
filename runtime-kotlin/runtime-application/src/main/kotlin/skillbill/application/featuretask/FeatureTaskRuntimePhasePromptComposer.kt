package skillbill.application.featuretask

import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.featuretask.model.FeatureTaskRuntimePhasePromptComposeInputs

object FeatureTaskRuntimePhasePromptComposer {
  fun compose(
    issueKey: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    configure: FeatureTaskRuntimePhasePromptComposeInputs.() -> FeatureTaskRuntimePhasePromptComposeInputs = { this },
  ): String = composePhasePrompt(
    configure(
      FeatureTaskRuntimePhasePromptComposeInputs(
        issueKey = issueKey,
        briefing = briefing,
      ),
    ),
  )

  internal fun budgetedAddonsFor(
    phaseId: String,
    selection: HydratedAgentAddonSelection,
  ): HydratedAgentAddonSelection = selection

  internal const val ADDON_CONTENT_PROJECTION_NAME: String = "agent_addon_content"
}

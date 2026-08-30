package skillbill.application.featuretask

import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.featuretask.model.FeatureTaskRuntimePhasePromptComposeInputs

internal object FeatureTaskRuntimePhasePromptComposer {
  fun compose(
    issueKey: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    configure: FeatureTaskRuntimePhasePromptComposeInputs.() -> FeatureTaskRuntimePhasePromptComposeInputs = { this },
  ): String = compose(
    configure(
      FeatureTaskRuntimePhasePromptComposeInputs(
        issueKey = issueKey,
        briefing = briefing,
      ),
    ),
  )

  fun compose(inputs: FeatureTaskRuntimePhasePromptComposeInputs): String = composePhasePrompt(inputs)

  internal fun budgetedAddonsFor(selection: HydratedAgentAddonSelection): HydratedAgentAddonSelection = selection

  internal const val ADDON_CONTENT_PROJECTION_NAME: String = "agent_addon_content"
}

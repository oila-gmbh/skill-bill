package skillbill.application.featuretask
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.featuretask.model.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.workflow.decomposition.model.IssueKey

object FeatureTaskRuntimePhasePromptComposer {
  fun compose(
    issueKey: IssueKey,
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

  fun budgetedAddonsFor(selection: HydratedAgentAddonSelection): HydratedAgentAddonSelection = selection
}

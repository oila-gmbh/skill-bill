package skillbill.engine

import skillbill.engine.featuretask.FeatureTaskRuntimePhasePromptComposer
import skillbill.engine.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.FeatureTaskRuntimePhasePromptComposeInputs

internal fun composePhasePrompt(inputs: FeatureTaskRuntimePhasePromptComposeInputs): String =
  FeatureTaskRuntimePhasePromptComposer.compose(inputs)

internal fun composePhasePrompt(
  issueKey: String,
  briefing: FeatureTaskRuntimePhaseLaunchBriefing,
  configure: FeatureTaskRuntimePhasePromptComposeInputs.() -> FeatureTaskRuntimePhasePromptComposeInputs = { this },
): String = composePhasePrompt(
  FeatureTaskRuntimePhasePromptComposeInputs(issueKey = issueKey, briefing = briefing).configure(),
)

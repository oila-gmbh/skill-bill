package skillbill.application

import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffEnvelope
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionField
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffPromptVisibility
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimePhaseLaunchBriefingSerializationTest {
  @Test
  fun `a briefing round trips through the durable artifact map`() {
    val briefing = briefing()

    assertEquals(briefing, FeatureTaskRuntimePhaseLaunchBriefing.fromArtifactMap(briefing.toArtifactMap()))
  }

  @Test
  fun `a legacy row carrying the removed upstream payload map loud-fails instead of migrating silently`() {
    val legacyRow = briefing().toArtifactMap() + ("upstream_outputs_by_phase_id" to mapOf("plan" to "raw payload"))

    val error = assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseLaunchBriefing.fromArtifactMap(legacyRow)
    }

    assertContains(error.message.orEmpty(), "upstream_outputs_by_phase_id")
    assertContains(error.message.orEmpty(), "handoff_envelope")
  }

  @Test
  fun `a row missing the handoff envelope loud-fails rather than defaulting to an empty delivery`() {
    val rowWithoutEnvelope = briefing().toArtifactMap() - "handoff_envelope"

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseLaunchBriefing.fromArtifactMap(rowWithoutEnvelope)
    }
  }

  private fun briefing() = FeatureTaskRuntimePhaseLaunchBriefing(
    phaseId = "implement",
    specReference = ".feature-specs/SKILL-137/spec.md",
    featureSize = "MEDIUM",
    acceptanceCriteria = listOf("AC-1"),
    mandatesAndOverrides = listOf("mandate-X"),
    handoffEnvelope = FeatureTaskRuntimeHandoffEnvelope(
      consumerPhaseId = "implement",
      projections = listOf(
        FeatureTaskRuntimeHandoffProjection(
          projectionName = "plan_receipt",
          sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput("plan"),
          projectionContractId = "feature_task_runtime.upstream_phase_receipt",
          projectionContractVersion = "0.1",
          promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
          fields = listOf(
            FeatureTaskRuntimeHandoffProjectionField(
              name = "phase_output_receipt",
              value = FeatureTaskRuntimeHandoffProjectionValue.Text("""{"plan":"ok"}"""),
            ),
          ),
        ),
      ),
    ),
    derivedContextKeys = emptyList(),
    briefingText = "# Feature-task-runtime phase briefing\nphase: implement\n",
  )
}

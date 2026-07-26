package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTaskRuntimeLaunchRejectionAttributionTest {
  @Test
  fun `audit rejection uses the rejected projection contract and its current producer iteration`() {
    val declarations = requireNotNull(
      FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations[
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
      ],
    ).projectionDeclarations
    val rejectedPlan = declarations.single {
      it.producerIteration.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
    }

    val attribution = resolveLaunchRejectionAttribution(
      declarations = declarations,
      projectionName = rejectedPlan.projectionName,
      currentProducerIteration = { phaseId ->
        when (phaseId) {
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN -> 2
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT -> 7
          else -> null
        }
      },
      fallbackProducerIteration = FeatureTaskRuntimeProducerIteration(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        7,
      ),
    )

    assertEquals(rejectedPlan.projectionContractId, attribution.projectionContractId)
    assertEquals(
      FeatureTaskRuntimeProducerIteration(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN, 2),
      attribution.producerIteration,
    )
  }
}

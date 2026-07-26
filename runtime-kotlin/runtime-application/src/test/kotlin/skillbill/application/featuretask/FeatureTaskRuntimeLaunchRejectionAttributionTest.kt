package skillbill.application.featuretask

import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTaskRuntimeLaunchRejectionAttributionTest {
  @Test
  fun `audit rejection uses the rejected projection contract and its current producer iteration`() {
    val declarations = requireNotNull(
      FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations[
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
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

  @Test
  fun `planning projection schema rejection preserves the rejected declaration identity`() {
    val declarations = requireNotNull(
      FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations[
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      ],
    ).projectionDeclarations
    val rejectedImplementation = declarations.single {
      it.producerIteration.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
    }
    val error = InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
      sourceLabel = "implement#produced_outputs",
      reason = "projection contract rejected the implementation receipt",
      projectionName = rejectedImplementation.projectionName,
    )

    val attribution = resolveLaunchRejectionAttribution(
      declarations = declarations,
      projectionName = requireNotNull(error.projectionName),
      currentProducerIteration = { phaseId ->
        when (phaseId) {
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN -> 5
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT -> 3
          else -> null
        }
      },
      fallbackProducerIteration = FeatureTaskRuntimeProducerIteration(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        5,
      ),
    )

    assertEquals(rejectedImplementation.projectionContractId, attribution.projectionContractId)
    assertEquals(
      FeatureTaskRuntimeProducerIteration(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT, 3),
      attribution.producerIteration,
    )
  }
}

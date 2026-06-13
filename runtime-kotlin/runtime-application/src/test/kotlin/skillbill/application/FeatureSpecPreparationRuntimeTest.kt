package skillbill.application

import skillbill.application.featuretask.FeatureSpecPreparationRuntime
import skillbill.featurespec.model.FeatureSpecPreparationDecision
import skillbill.featurespec.model.FeatureSpecPreparationIntake
import skillbill.featurespec.model.FeatureSpecPreparationMode
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureSpecPreparationRuntimeTest {
  @Test
  fun `feature-spec, feature-implement, and goal wrappers share the same preparation core`() {
    var invocationCount = 0
    val runtime = FeatureSpecPreparationRuntime { intake ->
      invocationCount += 1
      FeatureSpecPreparationDecision(
        issueKey = intake.issueKey,
        intendedOutcome = intake.intendedOutcome,
        acceptanceCriteria = intake.acceptanceCriteria,
        constraints = intake.constraints,
        nonGoals = intake.nonGoals,
        mode = FeatureSpecPreparationMode.DECOMPOSED,
      )
    }

    val intake = FeatureSpecPreparationIntake(
      issueKey = "SKILL-59",
      intendedOutcome = "decomposed",
      acceptanceCriteria = listOf("AC1"),
      constraints = listOf("Constraint 1"),
    )

    val featureSpecDecision = runtime.prepareForFeatureSpec(intake)
    val featureImplementDecision = runtime.prepareForFeatureImplement(intake)
    val goalDecision = runtime.prepareForGoal(intake)

    assertEquals(3, invocationCount)
    assertEquals(FeatureSpecPreparationMode.DECOMPOSED, featureSpecDecision.mode)
    assertEquals(FeatureSpecPreparationMode.DECOMPOSED, featureImplementDecision.mode)
    assertEquals(FeatureSpecPreparationMode.DECOMPOSED, goalDecision.mode)
    assertEquals(featureSpecDecision, featureImplementDecision)
    assertEquals(featureSpecDecision, goalDecision)
    assertEquals(featureImplementDecision, goalDecision)
  }
}

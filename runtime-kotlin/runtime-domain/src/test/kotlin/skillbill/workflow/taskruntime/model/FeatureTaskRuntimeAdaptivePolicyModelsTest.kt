package skillbill.workflow.taskruntime.model

import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureTaskRuntimeAdaptivePolicyModelsTest {
  @Test
  fun `skill 134 shaped work is large and requires decomposition`() {
    val signals = FeatureTaskRuntimeComplexitySignals(
      taskCount = 8,
      dependencyDepth = 4,
      moduleBreadth = 6,
      boundaryBreadth = 5,
      persistenceOrMigration = true,
      securityOrPrivacy = true,
      concurrencyOrLifecycle = true,
      processBoundaryOrCrashRecovery = true,
      platformCount = 2,
      expectedChangedPathCount = 80,
    )

    val decision = FeatureTaskRuntimeSizingPolicyResolver.resolve(signals)

    assertEquals(FeatureTaskRuntimeFeatureSize.LARGE, decision.featureSize)
    assertEquals(
      FeatureTaskRuntimeDecompositionRequirement.DECOMPOSITION_REQUIRED,
      decision.decompositionRequirement,
    )
    assertEquals(
      FeatureTaskRuntimePlanAdvance.REENTER_PLAN_FOR_DECOMPOSITION,
      FeatureTaskRuntimeDirectPlanGate.resolve(decision),
    )
  }

  @Test
  fun `persisted governed override permits direct implementation`() {
    val decision = FeatureTaskRuntimeSizingDecision(
      FeatureTaskRuntimeFeatureSize.LARGE,
      90,
      FeatureTaskRuntimeDecompositionRequirement.DECOMPOSITION_REQUIRED,
      listOf("bounded_score=90"),
    )
    val override = FeatureTaskRuntimeGovernedDirectOverride("override-1", "0.1", "Already decomposed child.", true)

    assertEquals(FeatureTaskRuntimePlanAdvance.IMPLEMENT, FeatureTaskRuntimeDirectPlanGate.resolve(decision, override))
  }

  @Test
  fun `inline request cannot reduce multi specialist review floor`() {
    val signals = FeatureTaskRuntimeComplexitySignals(4, 2, 4, 5, true, true, true, false, 1, 30)
    val sizing = FeatureTaskRuntimeSizingPolicyResolver.resolve(signals)

    val review = FeatureTaskRuntimeAdaptiveReviewPolicy.resolve(sizing, signals, CodeReviewExecutionMode.INLINE)

    assertEquals(FeatureTaskRuntimeReviewSubstanceDepth.MULTI_SPECIALIST, review.minimumDepth)
    assertEquals(FeatureTaskRuntimeResolvedReviewMode.PARALLEL_SPECIALIST, review.executionMode)
    assertTrue(review.requiredSpecialistAreas.containsAll(setOf("persistence", "security", "platform-correctness")))
  }

  @Test
  fun `passing focused result reuses only the semantic checkpoint`() {
    val result = FeatureTaskRuntimeFocusedQualityCheckpoint("checkpoint-1", "semantic-1", emptyList(), passed = true)

    assertTrue(result.reusableFor("semantic-1"))
    assertEquals(false, result.reusableFor("semantic-2"))
  }
}

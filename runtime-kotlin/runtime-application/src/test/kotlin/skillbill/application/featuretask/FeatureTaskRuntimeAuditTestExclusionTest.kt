package skillbill.application.featuretask

import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AUDIT_REPAIR_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItem
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Completeness audit judges whether required behavior exists, not whether it is well tested. Validation owns
 * test execution and failures, so a test-adequacy finding has no repair item that could close it against
 * read-only repository facts — it is rejected at the reconciler seam rather than handed to repair.
 */
class FeatureTaskRuntimeAuditTestExclusionTest {
  @Test
  fun `a test-adequacy finding is rejected as an audit gap at the reconciler seam`() {
    listOf(
      "The changed reconciler has no test coverage for the recurring path",
      "Test adequacy is insufficient for the new gate",
      "The migration is untested",
      "The store test has a missing assertion on the duplicate ordinal",
      "The wire decoder has no test fixture for the legacy payload",
    ).forEach { diagnosis ->
      val failure = assertFailsWith<InvalidWorkflowStateSchemaError> {
        reconcile(diagnosis = diagnosis)
      }
      assertContains(failure.message.orEmpty(), "excludes test adequacy")
      assertContains(failure.message.orEmpty(), "ac-001-gap-1")
    }
  }

  @Test
  fun `a behavior-absence finding remains an acceptable audit gap`() {
    val state = reconcile(diagnosis = "The append-only generation table is never written on audit settlement")

    assertContains(state.unresolvedGapLedger.unresolvedGaps.map { it.gapId }, "ac-001-gap-1")
  }

  @Test
  fun `repair and follow-up audit directives carry no build or test execution instruction`() {
    val repairAndAudit = listOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
    ).map { phaseId -> phaseId to requireNotNull(phaseDirectives[phaseId]) }

    repairAndAudit.forEach { (phaseId, directive) ->
      BUILD_AND_TEST_COMMANDS.forEach { command ->
        assertTrue(
          !directive.contains(command),
          "the $phaseId directive must not instruct a $command invocation as repair or audit evidence",
        )
      }
    }

    assertContains(
      requireNotNull(phaseDirectives[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT]),
      "do not run builds or tests here",
    )
    val auditDirective = requireNotNull(phaseDirectives[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT])
    assertContains(auditDirective, "read-only repository facts")
    assertContains(auditDirective, "validation owns test execution")
    assertContains(auditDirective, "blast_radius_inspection")
  }

  private fun reconcile(diagnosis: String) = FeatureTaskRuntimeAuditRepairReconciler.reconcile(
    AuditRepairReconciliation(
      prior = null,
      latestPlan = FeatureTaskRuntimeAuditRepairPlan(
        AUDIT_REPAIR_CONTRACT_VERSION,
        listOf(
          FeatureTaskRuntimeAuditGap(
            gapId = "ac-001-gap-1",
            acceptanceCriterionRef = "AC-001",
            acceptanceCriterionText = "The initial completeness audit persists one generation",
            failureEvidence = FeatureTaskRuntimeEvidence(
              observation = FeatureTaskRuntimeEvidence.Observation.REQUIRED_BEHAVIOR_ABSENT,
              artifactRef = "runtime-kotlin/runtime-application/Example.kt:Example",
              checkRef = "AC-001",
            ),
            diagnosis = diagnosis,
            affectedBoundary = "runtime-application",
            repairItems = listOf(
              FeatureTaskRuntimeRepairItem(
                repairItemId = "ac-001-gap-1-item-1",
                intendedOutcome = "The generation is appended on settlement",
                implementationActions = listOf("Append the generation in the settlement transaction"),
                affectedPathsOrSymbols = listOf("runtime-kotlin/runtime-application/Example.kt"),
                requiredVerification = listOf("Re-read the changed symbol"),
                dependsOn = emptyList(),
              ),
            ),
          ),
        ),
      ),
      repairResults = emptyList(),
      dispositions = null,
      repositoryFingerprint = "9f2c1ab",
      edgeIteration = null,
      auditWrite = true,
      auditScopeCriterionRefs = listOf("AC-001"),
    ),
  )

  private companion object {
    val BUILD_AND_TEST_COMMANDS = listOf("./gradlew", "gradlew check", "npm ", "npx ", "pytest", "cargo test")
  }
}

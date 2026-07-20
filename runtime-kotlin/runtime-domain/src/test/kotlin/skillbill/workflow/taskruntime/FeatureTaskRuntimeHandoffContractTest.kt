package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureTaskRuntimeHandoffContractTest {
  private val runInvariants =
    FeatureTaskRuntimeRunInvariants(
      specReference = ".feature-specs/SKILL-65/spec.md",
      acceptanceCriteria = listOf("AC1", "AC2"),
      mandatesAndOverrides = listOf("No installer runs"),
    )

  @Test
  fun `latest-iteration selection keeps the highest iteration per phase across fix loops`() {
    val recorded =
      listOf(
        FeatureTaskRuntimePhaseOutput("review", iteration = 1, payload = "review-v1"),
        FeatureTaskRuntimePhaseOutput("implement", iteration = 1, payload = "impl-v1"),
        FeatureTaskRuntimePhaseOutput("implement", iteration = 2, payload = "impl-v2"),
        FeatureTaskRuntimePhaseOutput("review", iteration = 2, payload = "review-v2"),
      )
    val latest = FeatureTaskRuntimeHandoffContract.selectLatestOutputsByPhase(recorded)
    assertEquals("impl-v2", latest.getValue("implement").payload)
    assertEquals("review-v2", latest.getValue("review").payload)
    assertEquals(2, latest.getValue("implement").iteration)
  }

  @Test
  fun `latest-iteration selection prefers the last-recorded entry on an iteration tie`() {
    val recorded =
      listOf(
        FeatureTaskRuntimePhaseOutput("plan", iteration = 1, payload = "plan-a"),
        FeatureTaskRuntimePhaseOutput("plan", iteration = 1, payload = "plan-b"),
      )
    val latest = FeatureTaskRuntimeHandoffContract.selectLatestOutputsByPhase(recorded)
    assertEquals("plan-b", latest.getValue("plan").payload)
  }

  @Test
  fun `resolveUpstreamOutputs selects only declared dependencies at their latest iteration`() {
    val auditDeclaration =
      FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
        .getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
    val recorded =
      listOf(
        FeatureTaskRuntimePhaseOutput("plan", iteration = 1, payload = "plan-v1"),
        FeatureTaskRuntimePhaseOutput("implement", iteration = 1, payload = "impl-v1"),
        FeatureTaskRuntimePhaseOutput("implement", iteration = 2, payload = "impl-v2"),
        FeatureTaskRuntimePhaseOutput("review", iteration = 1, payload = "review-v1"),
        FeatureTaskRuntimePhaseOutput("validate", iteration = 1, payload = "validate-v1"),
      )
    val resolved = FeatureTaskRuntimeHandoffContract.resolveUpstreamOutputs(auditDeclaration, recorded)
    assertEquals(setOf("plan", "implement"), resolved.outputsByPhaseId.keys)
    assertEquals("impl-v2", resolved.outputsByPhaseId.getValue("implement").payload)
    assertTrue("validate" !in resolved.outputsByPhaseId)
    // Audit runs before review under the audit-first order and no longer consumes review output.
    assertTrue("review" !in resolved.outputsByPhaseId)
  }

  @Test
  fun `resolveUpstreamOutputs omits a declared dependency that has no recorded output`() {
    val implementDeclaration =
      FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
        .getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT)
    val resolved = FeatureTaskRuntimeHandoffContract.resolveUpstreamOutputs(implementDeclaration, emptyList())
    assertTrue(resolved.outputsByPhaseId.isEmpty())
  }

  @Test
  fun `assembleHandoff carries run-invariants, resolved upstream, and derived context`() {
    val reviewDeclaration =
      FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
        .getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
    val recorded = listOf(FeatureTaskRuntimePhaseOutput("implement", iteration = 3, payload = "impl-v3"))
    val handoff =
      FeatureTaskRuntimeHandoffContract.assembleHandoff(reviewDeclaration, runInvariants, recorded)
    assertEquals("review", handoff.phaseId)
    assertEquals(runInvariants, handoff.runInvariants)
    assertEquals("impl-v3", handoff.upstreamOutputs.outputsByPhaseId.getValue("implement").payload)
    assertEquals(listOf("diff"), handoff.derivedContextKeys)
  }

  @Test
  fun `run-invariant presence is enforced - blank spec reference fails loudly`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeRunInvariants(
        specReference = "   ",
        acceptanceCriteria = listOf("AC1"),
        mandatesAndOverrides = emptyList(),
      )
    }
  }

  @Test
  fun `run-invariant presence is enforced - empty acceptance criteria fails loudly`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeRunInvariants(
        specReference = ".feature-specs/SKILL-65/spec.md",
        acceptanceCriteria = emptyList(),
        mandatesAndOverrides = emptyList(),
      )
    }
  }

  @Test
  fun `run-invariant presence is enforced - blank criterion entry fails loudly`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeRunInvariants(
        specReference = ".feature-specs/SKILL-65/spec.md",
        acceptanceCriteria = listOf("AC1", " "),
        mandatesAndOverrides = emptyList(),
      )
    }
  }
}

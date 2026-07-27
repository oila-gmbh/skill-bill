package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeAgentAssignment
import skillbill.application.model.FeatureTaskRuntimeModelAssignment
import skillbill.application.model.FeatureTaskRuntimeRunEvent
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.ExecutionTier
import skillbill.config.model.PhaseModelDirective
import skillbill.install.model.InstallAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeModelDirectiveRunnerTest {
  @Test
  fun `matrix directives populate phase launches and started events`() {
    val harness = runnerHarness(agentAssignment = FeatureTaskRuntimeAgentAssignment(override = "claude"))
    val matrix = ExecutionMatrix(
      agents = mapOf(
        InstallAgent.CLAUDE to mapOf(
          ExecutionTier.REASONING to PhaseModelDirective("claude-opus", "high"),
          ExecutionTier.IMPLEMENTATION to PhaseModelDirective("claude-sonnet", "medium"),
        ),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(
      harness.runner.run(harness.request().copy(modelAssignment = FeatureTaskRuntimeModelAssignment(matrix = matrix))),
    )

    val plan = harness.requestForPhase("plan").skillRunRequest
    val implement = harness.requestForPhase("implement").skillRunRequest
    assertEquals("claude-opus", plan.modelOverride)
    assertEquals("high", plan.effortOverride)
    assertEquals("claude-sonnet", implement.modelOverride)
    assertEquals("medium", implement.effortOverride)
    val planStarted = harness.events.filterIsInstance<FeatureTaskRuntimeRunEvent.PhaseStarted>()
      .single { it.phaseId == "plan" }
    assertEquals("claude-opus", planStarted.model)
    assertEquals("high", planStarted.effort)
  }

  @Test
  fun `zero config model assignment keeps launch directives absent`() {
    val harness = runnerHarness()

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertTrue(
      harness.launcher.requests.all { request ->
        request.skillRunRequest.modelOverride == null && request.skillRunRequest.effortOverride == null
      },
    )
  }

  private fun RunnerHarness.requestForPhase(phaseId: String) = launcher.requests.single { request ->
    requireNotNull(request.skillRunRequest.promptOverride).contains("Phase: $phaseId ")
  }

  @Test
  fun `cursor model directive renders model with effort bracket syntax`() {
    val harness = runnerHarness(agentAssignment = FeatureTaskRuntimeAgentAssignment(override = "cursor"))
    val matrix = ExecutionMatrix(
      agents = mapOf(
        InstallAgent.CURSOR to mapOf(
          ExecutionTier.REASONING to PhaseModelDirective("claude-opus-4-8", "high"),
          ExecutionTier.IMPLEMENTATION to PhaseModelDirective("claude-sonnet-4-5", "medium"),
        ),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(
      harness.runner.run(harness.request().copy(modelAssignment = FeatureTaskRuntimeModelAssignment(matrix = matrix))),
    )

    val plan = harness.requestForPhase("plan").skillRunRequest
    val implement = harness.requestForPhase("implement").skillRunRequest
    assertEquals("claude-opus-4-8[effort=high]", plan.modelOverride)
    assertEquals("high", plan.effortOverride)
    assertEquals("claude-sonnet-4-5[effort=medium]", implement.modelOverride)
    assertEquals("medium", implement.effortOverride)
  }

  @Test
  fun `cursor model-only directive renders without effort brackets`() {
    val harness = runnerHarness(agentAssignment = FeatureTaskRuntimeAgentAssignment(override = "cursor"))
    val matrix = ExecutionMatrix(
      agents = mapOf(
        InstallAgent.CURSOR to mapOf(
          ExecutionTier.REASONING to PhaseModelDirective("claude-opus-4-8", null),
        ),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(
      harness.runner.run(harness.request().copy(modelAssignment = FeatureTaskRuntimeModelAssignment(matrix = matrix))),
    )

    val plan = harness.requestForPhase("plan").skillRunRequest
    assertEquals("claude-opus-4-8", plan.modelOverride)
    assertEquals(null, plan.effortOverride)
  }

  @Test
  fun `cursor invoked-agent route selects cursor adapter`() {
    val harness = runnerHarness(agentAssignment = FeatureTaskRuntimeAgentAssignment(override = "cursor"))

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertTrue(harness.launcher.requests.all { it.configuredAgentOverrideId == "cursor" })
  }

  @Test
  fun `cursor phase-agent override route selects cursor adapter`() {
    val harness = runnerHarness(
      agentAssignment = FeatureTaskRuntimeAgentAssignment(
        perPhaseAgentIds = mapOf("plan" to "cursor"),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val planRequest = harness.requestForPhase("plan")
    assertEquals("cursor", planRequest.invokedAgentId)

    val nonPlanRequests = harness.launcher.requests.filter {
      !it.skillRunRequest.promptOverride.orEmpty().contains("Phase: plan ")
    }
    assertTrue(nonPlanRequests.all { it.invokedAgentId != "cursor" })
  }

  @Test
  fun `cursor parallel-review route selects cursor adapter`() {
    val harness = runnerHarness(
      agentAssignment = FeatureTaskRuntimeAgentAssignment(
        perPhaseAgentIds = mapOf("review" to "cursor"),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val reviewRequest = harness.launcher.requests.single {
      it.skillRunRequest.promptOverride.orEmpty().contains("Phase: review ")
    }
    assertEquals("cursor", reviewRequest.invokedAgentId)
  }
}

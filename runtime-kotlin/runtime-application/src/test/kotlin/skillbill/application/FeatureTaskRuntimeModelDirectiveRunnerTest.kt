package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeAgentAssignment
import skillbill.application.model.FeatureTaskRuntimeModelAssignment
import skillbill.application.model.FeatureTaskRuntimeRunEvent
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.ExecutionTier
import skillbill.config.model.PhaseModelDirective
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
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
  fun `the persisted launched model is the merged string cursor was actually launched with`() {
    val harness = runnerHarness(agentAssignment = FeatureTaskRuntimeAgentAssignment(override = "cursor"))
    val matrix = ExecutionMatrix(
      agents = mapOf(
        InstallAgent.CURSOR to mapOf(
          ExecutionTier.REASONING to PhaseModelDirective("claude-opus-4-8", "high"),
        ),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(
      harness.runner.run(harness.request().copy(modelAssignment = FeatureTaskRuntimeModelAssignment(matrix = matrix))),
    )

    val plan = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID)).getValue("plan")
    // Not the pre-merge directive model: a recomputed merge would record a value the child never saw.
    assertEquals(harness.requestForPhase("plan").skillRunRequest.modelOverride, plan.launchedModel)
    assertEquals("claude-opus-4-8[effort=high]", plan.launchedModel)
    // The merged model already carries the effort; persisting it twice would let the two drift.
    assertEquals(null, plan.launchedEffort)
  }

  @Test
  fun `a phase with no resolved directive persists neither launched field`() {
    val harness = runnerHarness()

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val plan = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID)).getValue("plan")
    assertEquals(null, plan.launchedModel)
    assertEquals(null, plan.launchedEffort)
    assertTrue("launched_model" !in plan.toArtifactMap())
  }

  @Test
  fun `a phase blocked after its child ran keeps the model the child was launched with`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher {
        AgentRunLaunchFacts(
          agent = InstallAgent.CLAUDE,
          exitStatus = 1,
          stdout = "",
          stderr = "boom",
          timedOut = false,
          spawnFailed = false,
        )
      },
      agentAssignment = FeatureTaskRuntimeAgentAssignment(override = "claude"),
    )
    val matrix = ExecutionMatrix(
      agents = mapOf(
        InstallAgent.CLAUDE to mapOf(
          ExecutionTier.REASONING to PhaseModelDirective("claude-opus", "high"),
          ExecutionTier.IMPLEMENTATION to PhaseModelDirective("claude-sonnet", "medium"),
        ),
      ),
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(
      harness.runner.run(harness.request().copy(modelAssignment = FeatureTaskRuntimeModelAssignment(matrix = matrix))),
    )

    // A non-zero exit is a child that provably launched and ran, so the blocked record must still
    // answer "which model ran?" rather than clearing the running write's stamp.
    assertEquals("preplan", blocked.lastIncompletePhase)
    val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID)).getValue("preplan")
    assertEquals(harness.launcher.requests.last().skillRunRequest.modelOverride, record.launchedModel)
    assertEquals("claude-sonnet", record.launchedModel)
    assertEquals("medium", record.launchedEffort)
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

package skillbill.application

import skillbill.application.model.AgentRunStartRequest
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.SkillRunRequest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentRunServiceTest {
  @Test
  fun `configured override wins over invoked bill-feature-goal agent`() {
    val launcher = RecordingAgentRunLauncher()
    val service = AgentRunService(launcher)

    val result = service.launch(
      AgentRunStartRequest(
        invokedAgentId = "claude",
        configuredAgentOverrideId = "codex",
        skillRunRequest = skillRunRequest(),
      ),
    )

    assertEquals(InstallAgent.CLAUDE, result.resolution.invokedAgent)
    assertEquals(InstallAgent.CODEX, result.resolution.configuredOverrideAgent)
    assertEquals(InstallAgent.CODEX, result.resolution.effectiveAgent)
    assertEquals("codex", launcher.requests.single().agentId)
  }

  @Test
  fun `invoked bill-feature-goal agent is the default without hardcoded fallback`() {
    val launcher = RecordingAgentRunLauncher()
    val service = AgentRunService(launcher)

    val result = service.launch(
      AgentRunStartRequest(
        invokedAgentId = "opencode",
        configuredAgentOverrideId = null,
        skillRunRequest = skillRunRequest(),
      ),
    )

    assertEquals(InstallAgent.OPENCODE, result.resolution.effectiveAgent)
    assertEquals("opencode", launcher.requests.single().agentId)
  }

  @Test
  fun `unknown configured agent fails loudly before launch`() {
    val launcher = RecordingAgentRunLauncher()
    val service = AgentRunService(launcher)

    assertFailsWith<IllegalArgumentException> {
      service.launch(
        AgentRunStartRequest(
          invokedAgentId = "codex",
          configuredAgentOverrideId = "unknown",
          skillRunRequest = skillRunRequest(),
        ),
      )
    }

    assertEquals(emptyList(), launcher.requests)
  }

  private fun skillRunRequest(): SkillRunRequest = SkillRunRequest(
    issueKey = "SKILL-56",
    repoRoot = Path.of("/tmp/skillbill-agent-run-service"),
    subtaskId = 2,
    dbPathOverride = "/tmp/skillbill-agent-run-service/metrics.db",
  )
}

private class RecordingAgentRunLauncher : AgentRunLauncher {
  val requests: MutableList<AgentRunLaunchRequest> = mutableListOf()

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    requests += request
    return AgentRunLaunchFacts(
      agent = InstallAgent.fromId(request.agentId),
      exitStatus = 0,
      stdout = "diagnostic",
      stderr = "",
      timedOut = false,
      spawnFailed = false,
    )
  }
}

package skillbill.application

import skillbill.application.model.AgentRunStartRequest
import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.install.model.InstallAgent
import skillbill.model.RuntimeContext
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AgentRunServiceRuntimeComponentTest {
  @Test
  fun `runtime component exposes agent run service with filesystem launcher binding`() {
    val tempDir = Files.createTempDirectory("skillbill-agent-run-component")
    val service = RuntimeComponent::class.create(
      RuntimeContext(
        dbPathOverride = tempDir.resolve("metrics.db").toString(),
        environment = emptyMap(),
        userHome = tempDir,
      ),
    ).agentRunService

    val result = service.launch(
      AgentRunStartRequest(
        invokedAgentId = "copilot",
        skillRunRequest = SkillRunRequest(
          issueKey = "SKILL-56",
          repoRoot = tempDir,
          subtaskId = 2,
        ),
      ),
    )

    assertEquals(InstallAgent.COPILOT, result.resolution.effectiveAgent)
    assertIs<UnsupportedAgentRunLaunch>(result.launchOutcome)
  }
}

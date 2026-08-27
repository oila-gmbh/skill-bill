package skillbill.application

import skillbill.application.model.AgentRunStartRequest
import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.install.model.InstallAgent
import skillbill.model.RuntimeContext
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.SkillRunRequest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
        invokedAgentId = "junie",
        skillRunRequest = SkillRunRequest(
          issueKey = "SKILL-56",
          repoRoot = tempDir,
          subtaskId = 2,
          promptOverride = "Phase: validate",
        ),
      ),
    )

    assertEquals(InstallAgent.JUNIE, result.resolution.effectiveAgent)
    val facts = assertIs<AgentRunLaunchFacts>(result.launchOutcome)
    assertTrue(facts.spawnFailed)
    assertContains(facts.stderr, "'junie' is not on PATH")
  }
}

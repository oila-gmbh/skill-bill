package skillbill.ports.agentrun

import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.SkillRunRequest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class AgentRunLauncherModelsTest {
  @Test
  fun `skill run request allows no wall-clock cap and validates positive caps and subtask id`() {
    SkillRunRequest(issueKey = "SKILL-56", repoRoot = Path.of("."))

    assertFailsWith<IllegalArgumentException> {
      SkillRunRequest(issueKey = "SKILL-56", repoRoot = Path.of("."), timeout = 0.seconds)
    }
    assertFailsWith<IllegalArgumentException> {
      SkillRunRequest(issueKey = "SKILL-56", repoRoot = Path.of("."), subtaskId = 0)
    }
  }

  @Test
  fun `launch facts do not allow terminal process status on timeout or spawn failure`() {
    assertFailsWith<IllegalArgumentException> {
      AgentRunLaunchFacts(
        agent = InstallAgent.CODEX,
        exitStatus = 1,
        stdout = "",
        stderr = "timeout",
        timedOut = true,
        spawnFailed = false,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      AgentRunLaunchFacts(
        agent = InstallAgent.CODEX,
        exitStatus = 1,
        stdout = "",
        stderr = "spawn failed",
        timedOut = false,
        spawnFailed = true,
      )
    }
  }
}

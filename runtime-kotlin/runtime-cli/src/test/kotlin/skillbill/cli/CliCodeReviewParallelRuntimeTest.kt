package skillbill.cli

import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CliCodeReviewParallelRuntimeTest {
  @Test
  fun `code-review-parallel command is registered and shows help`() {
    val help = CliRuntime.run(listOf("code-review-parallel", "--help"), CliRuntimeContext())

    assertEquals(0, help.exitCode, help.stdout)
    assertContains(help.stdout, "--agent1")
    assertContains(help.stdout, "--agent2")
    assertContains(help.stdout, "--scope")
  }

  @Test
  fun `code-review-parallel fails with usage error when agent2 is omitted`() {
    val result = CliRuntime.run(
      listOf("code-review-parallel", "--agent1", "claude"),
      CliRuntimeContext(),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "--agent2")
  }

  @Test
  fun `code-review-parallel fails with usage error when agent2 is unsupported`() {
    val tempDir = createGitRepo()
    createStagedFile(tempDir)
    val result = CliRuntime.run(
      listOf(
        "code-review-parallel",
        "--agent1",
        "claude",
        "--agent2",
        "not-a-real-agent",
        "--scope",
        "staged",
        "--repo-root",
        tempDir.toString(),
      ),
      CliRuntimeContext(agentRunLauncher = NoOpAgentRunLauncher()),
    )

    assertEquals(1, result.exitCode, result.stdout)
  }

  @Test
  fun `code-review-parallel fails when agent1 and agent2 are identical`() {
    val tempDir = createGitRepo()
    createStagedFile(tempDir)
    val result = CliRuntime.run(
      listOf(
        "code-review-parallel",
        "--agent1",
        "claude",
        "--agent2",
        "claude",
        "--scope",
        "staged",
        "--repo-root",
        tempDir.toString(),
      ),
      CliRuntimeContext(agentRunLauncher = NoOpAgentRunLauncher()),
    )

    assertEquals(1, result.exitCode, result.stdout)
  }

  @Test
  fun `code-review-parallel rejects invalid scope value`() {
    val result = CliRuntime.run(
      listOf("code-review-parallel", "--agent1", "claude", "--agent2", "codex", "--scope", "invalid-scope"),
      CliRuntimeContext(),
    )

    assertEquals(1, result.exitCode, result.stdout)
  }

  @Test
  fun `code-review-parallel runs both agents and exits 0 on success`() {
    val tempDir = createGitRepo()
    createStagedFile(tempDir)
    val launcher = ParallelReviewSuccessLauncher()
    val result = CliRuntime.run(
      listOf(
        "code-review-parallel",
        "--agent1",
        "claude",
        "--agent2",
        "codex",
        "--scope",
        "staged",
        "--repo-root",
        tempDir.toString(),
      ),
      CliRuntimeContext(agentRunLauncher = launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(2, launcher.launchCount)
  }

  @Test
  fun `code-review-parallel exits 1 when a lane fails`() {
    val tempDir = createGitRepo()
    createStagedFile(tempDir)
    val launcher = ParallelReviewFailFirstLaneLauncher()
    val result = CliRuntime.run(
      listOf(
        "code-review-parallel",
        "--agent1",
        "claude",
        "--agent2",
        "codex",
        "--scope",
        "staged",
        "--repo-root",
        tempDir.toString(),
      ),
      CliRuntimeContext(agentRunLauncher = launcher),
    )

    assertEquals(1, result.exitCode, result.stdout)
  }

  @Test
  fun `code-review-parallel resolves agent1 from SKILL_BILL_AGENT env`() {
    val tempDir = createGitRepo()
    createStagedFile(tempDir)
    val launcher = RecordingParallelLauncher()
    val result = CliRuntime.run(
      listOf(
        "code-review-parallel",
        "--agent2",
        "claude",
        "--scope",
        "staged",
        "--repo-root",
        tempDir.toString(),
      ),
      CliRuntimeContext(
        environment = System.getenv() + mapOf("SKILL_BILL_AGENT" to "opencode"),
        agentRunLauncher = launcher,
      ),
    )

    // agent1 resolves to opencode, but agent2 is claude so no duplicate error
    assertEquals(0, result.exitCode, result.stdout)
    assertFalse(launcher.agentIds.isEmpty())
  }

  @Test
  fun `code-review-parallel defaults agent1 to codex when nothing resolves`() {
    val tempDir = createGitRepo()
    createStagedFile(tempDir)
    val launcher = RecordingParallelLauncher()
    val result = CliRuntime.run(
      listOf(
        "code-review-parallel",
        "--agent2",
        "claude",
        "--scope",
        "staged",
        "--repo-root",
        tempDir.toString(),
      ),
      CliRuntimeContext(
        environment = emptyMap(),
        agentRunLauncher = launcher,
      ),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(launcher.agentIds.toSet(), "codex")
  }

  @Test
  fun `code-review-parallel same agent via env and explicit agent2 exits with usage error`() {
    val tempDir = createGitRepo()
    createStagedFile(tempDir)
    val result = CliRuntime.run(
      listOf(
        "code-review-parallel",
        "--agent2",
        "codex",
        "--scope",
        "staged",
        "--repo-root",
        tempDir.toString(),
      ),
      CliRuntimeContext(
        environment = emptyMap(),
        agentRunLauncher = NoOpAgentRunLauncher(),
      ),
    )

    // agent1 defaults to codex; agent2 is codex → duplicate error
    assertEquals(1, result.exitCode, result.stdout)
  }
}

private fun createGitRepo(): Path {
  val dir = Files.createTempDirectory("cli-parallel-review-git")
  ProcessBuilder("git", "init", dir.toString()).start().waitFor()
  ProcessBuilder("git", "-C", dir.toString(), "config", "user.email", "test@test.com").start().waitFor()
  ProcessBuilder("git", "-C", dir.toString(), "config", "user.name", "Test").start().waitFor()
  return dir
}

private fun createStagedFile(dir: Path) {
  val file = dir.resolve("Test.kt")
  Files.writeString(file, "fun main() {}\n")
  ProcessBuilder("git", "-C", dir.toString(), "add", "Test.kt").start().waitFor()
}

private class NoOpAgentRunLauncher : AgentRunLauncher {
  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome = AgentRunLaunchFacts(
    agent = InstallAgent.fromNormalizedId(request.agentId, label = "agentId"),
    exitStatus = 0,
    stdout = "",
    stderr = "",
    timedOut = false,
    spawnFailed = false,
  )
}

private class ParallelReviewSuccessLauncher : AgentRunLauncher {
  private val count = AtomicInteger(0)
  val launchCount: Int get() = count.get()

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    count.incrementAndGet()
    return AgentRunLaunchFacts(
      agent = InstallAgent.fromNormalizedId(request.agentId, label = "agentId"),
      exitStatus = 0,
      stdout = "- [F-001] Major | High | A.kt:1 | Issue",
      stderr = "",
      timedOut = false,
      spawnFailed = false,
    )
  }
}

private class ParallelReviewFailFirstLaneLauncher : AgentRunLauncher {
  private var callCount = 0

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    callCount++
    val agent = InstallAgent.fromNormalizedId(request.agentId, label = "agentId")
    return if (callCount == 1) {
      AgentRunLaunchFacts(
        agent = agent,
        exitStatus = null,
        stdout = "",
        stderr = "",
        timedOut = true,
        spawnFailed = false,
      )
    } else {
      AgentRunLaunchFacts(
        agent = agent,
        exitStatus = 0,
        stdout = "",
        stderr = "",
        timedOut = false,
        spawnFailed = false,
      )
    }
  }
}

private class RecordingParallelLauncher : AgentRunLauncher {
  val agentIds: MutableList<String> = mutableListOf()

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    agentIds += request.agentId
    return AgentRunLaunchFacts(
      agent = InstallAgent.fromNormalizedId(request.agentId, label = "agentId"),
      exitStatus = 0,
      stdout = "",
      stderr = "",
      timedOut = false,
      spawnFailed = false,
    )
  }
}

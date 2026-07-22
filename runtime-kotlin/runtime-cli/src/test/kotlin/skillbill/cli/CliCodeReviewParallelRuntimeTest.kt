package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.ConversationIsolation
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.review.model.NativeReviewWorkerRequest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

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
    assertContains(result.stdout, "path=\"Test.kt\" | line=1")
    assertContains(result.stdout, "[claude")
  }

  @Test
  fun `code-review-parallel passes model2 override to the alternative lane only`() {
    val tempDir = createGitRepo()
    createStagedFile(tempDir)
    val launcher = RecordingParallelLauncher()
    val result = CliRuntime.run(
      listOf(
        "code-review-parallel",
        "--agent1",
        "claude",
        "--agent2",
        "codex",
        "--model2",
        "gpt-5.3-codex-spark",
        "--scope",
        "staged",
        "--repo-root",
        tempDir.toString(),
      ),
      CliRuntimeContext(agentRunLauncher = launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("gpt-5.3-codex-spark", launcher.modelsByAgent["codex"])
    assertNull(launcher.modelsByAgent["claude"])
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
    assertContains(result.stdout, "Lane status")
    assertContains(result.stdout, "claude: failed (agent timed out)")
    assertContains(result.stdout, "codex: ok")
  }

  @Test
  fun `code-review-parallel exits 1 when both lanes fail and emits both failure labels`() {
    val tempDir = createGitRepo()
    createStagedFile(tempDir)
    val launcher = BothFailLauncher()
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
    assertContains(result.stdout, "claude: failed")
    assertContains(result.stdout, "codex: failed")
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
        environment = System.getenv() + mapOf("SKILL_BILL_AGENT" to "junie"),
        agentRunLauncher = launcher,
      ),
    )

    // SKILL-95: opencode is prose-only, so agent1 now resolves to junie (a supported runtime agent);
    // agent2 is claude so no duplicate error.
    assertEquals(0, result.exitCode, result.stdout)
    assertFalse(launcher.agentIds.isEmpty())
  }

  @Test
  fun `code-review-parallel refuses upfront when a resolved lane agent is opencode`() {
    // SKILL-95: opencode is prose-only. A lane resolving to opencode (agent1 via SKILL_BILL_AGENT, or
    // agent2 explicitly) must fail fast with the actionable message — not degrade to a one-lane review
    // — and spawn no lane.
    val tempDir = createGitRepo()
    createStagedFile(tempDir)
    val launcher = RecordingParallelLauncher()

    val viaAgent1 = CliRuntime.run(
      listOf("code-review-parallel", "--agent2", "claude", "--scope", "staged", "--repo-root", tempDir.toString()),
      CliRuntimeContext(environment = mapOf("SKILL_BILL_AGENT" to "opencode"), agentRunLauncher = launcher),
    )
    assertEquals(1, viaAgent1.exitCode, viaAgent1.stdout)
    assertContains(viaAgent1.stdout, "Runtime mode is not supported on opencode")
    assertContains(viaAgent1.stdout, "bill-feature-task-prose")

    val viaAgent2 = CliRuntime.run(
      listOf(
        "code-review-parallel",
        "--agent1",
        "claude",
        "--agent2",
        "opencode",
        "--scope",
        "staged",
        "--repo-root",
        tempDir.toString(),
      ),
      CliRuntimeContext(environment = emptyMap(), agentRunLauncher = launcher),
    )
    assertEquals(1, viaAgent2.exitCode, viaAgent2.stdout)
    assertContains(viaAgent2.stdout, "Runtime mode is not supported on opencode")
    // Refused before either lane runs.
    assertEquals(emptyList(), launcher.agentIds, viaAgent2.stdout)
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

private abstract class ParallelTestAgentRunLauncher : AgentRunLauncher {
  override fun launchNativeReview(request: NativeReviewWorkerRequest): AgentRunLaunchOutcome = launch(
    AgentRunLaunchRequest(
      agentId = request.agentId,
      skillRunRequest = SkillRunRequest(
        issueKey = request.issueKey,
        repoRoot = request.repoRoot,
        timeout = request.timeout,
        promptOverride = request.prompt,
        modelOverride = request.modelOverride,
        conversationIsolation = ConversationIsolation.NONE,
        reviewEvidenceBroker = request.broker,
        nativeReviewOperations = request.operations,
      ),
    ),
  )
}

private class NoOpAgentRunLauncher : ParallelTestAgentRunLauncher() {
  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome = AgentRunLaunchFacts(
    agent = InstallAgent.fromNormalizedId(request.agentId, label = "agentId"),
    exitStatus = 0,
    stdout = "",
    stderr = "",
    timedOut = false,
    spawnFailed = false,
  )
}

private class ParallelReviewSuccessLauncher : ParallelTestAgentRunLauncher() {
  private val count = AtomicInteger(0)
  val launchCount: Int get() = count.get()

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    count.incrementAndGet()
    return AgentRunLaunchFacts(
      agent = InstallAgent.fromNormalizedId(request.agentId, label = "agentId"),
      exitStatus = 0,
      stdout = "- [F-001] Major | High | Test.kt:1 | Issue",
      stderr = "",
      timedOut = false,
      spawnFailed = false,
    )
  }
}

private class ParallelReviewFailFirstLaneLauncher : ParallelTestAgentRunLauncher() {
  // Route the failure by agent id, not call order: the two lanes launch concurrently, so a
  // call-count check is nondeterministic. agent1 (claude) is the failing lane.
  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    val agent = InstallAgent.fromNormalizedId(request.agentId, label = "agentId")
    return if (request.agentId == "claude") {
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

private class BothFailLauncher : ParallelTestAgentRunLauncher() {
  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome = AgentRunLaunchFacts(
    agent = InstallAgent.fromNormalizedId(request.agentId, label = "agentId"),
    exitStatus = null,
    stdout = "",
    stderr = "",
    timedOut = true,
    spawnFailed = false,
  )
}

private class RecordingParallelLauncher : ParallelTestAgentRunLauncher() {
  private val lock = Any()
  val agentIds: MutableList<String> = mutableListOf()
  val modelsByAgent: MutableMap<String, String?> = mutableMapOf()

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    // Lanes launch on concurrent threads; guard the recording collections.
    synchronized(lock) {
      agentIds += request.agentId
      modelsByAgent[request.agentId] = request.skillRunRequest.modelOverride
    }
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

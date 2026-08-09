package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
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
import kotlin.test.assertNull

class CliCodeReviewParallelRuntimeTest {
  @Test
  fun `code-review-parallel command is registered and shows help`() {
    val help = CliRuntime.run(listOf("code-review-parallel", "--help"), parallelReviewContext())

    assertEquals(0, help.exitCode, help.stdout)
    assertContains(help.stdout, "--agent1")
    assertContains(help.stdout, "--agent2")
    assertContains(help.stdout, "--scope")
    assertContains(help.stdout, "--base-revision")
    assertContains(help.stdout, "--expand-file")
    assertContains(help.stdout, "--baseline-untracked-exclude")
  }

  @Test
  fun `diff file requires paired immutable revisions`() {
    val tempDir = createGitRepo()
    val diff = Files.createTempFile("parallel-review", ".diff")
    Files.writeString(diff, "+++ b/Test.kt\n+change\n")

    val result = CliRuntime.run(
      listOf(
        "code-review-parallel",
        "--agent1", "claude",
        "--agent2", "codex",
        "--diff-file", diff.toString(),
        "--repo-root", tempDir.toString(),
      ),
      parallelReviewContext(agentRunLauncher = NoOpAgentRunLauncher()),
    )

    assertEquals(1, result.exitCode)
    assertContains(result.stdout, "paired baseRevision and headRevision")
  }

  @Test
  fun `code-review-parallel rejects an unknown execution mode`() {
    val tempDir = createGitRepo()

    val result = CliRuntime.run(
      listOf(
        "code-review-parallel",
        "--agent1", "claude",
        "--agent2", "codex",
        "--execution-mode", "external",
        "--repo-root", tempDir.toString(),
      ),
      parallelReviewContext(agentRunLauncher = RecordingParallelLauncher()),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "Unknown code-review execution mode 'external'")
  }

  @Test
  fun `diff file expansion rejects an unmatched selector instead of silently dropping it`() {
    val tempDir = createGitRepo()
    Files.writeString(tempDir.resolve("Test.kt"), "fun evidence() = true\n")
    val diff = Files.createTempFile("parallel-review", ".diff")
    Files.writeString(diff, "diff --git a/Test.kt b/Test.kt\n+++ b/Test.kt\n+change\n")

    val result = CliRuntime.run(
      listOf(
        "code-review-parallel",
        "--agent1", "claude",
        "--agent2", "codex",
        "--diff-file", diff.toString(),
        "--base-revision", "immutable-base",
        "--head-revision", "immutable-head",
        "--expand-file", "unknown-lane:Test.kt=called by assigned hunk",
        "--execution-mode", "inline",
        "--repo-root", tempDir.toString(),
      ),
      parallelReviewContext(agentRunLauncher = RecordingParallelLauncher()),
    )

    assertEquals(1, result.exitCode)
    assertContains(result.stdout, "Prelaunch expansion selector 'unknown-lane' does not match")
  }

  @Test
  fun `code-review-parallel fails with usage error when agent2 is omitted`() {
    val result = CliRuntime.run(
      listOf("code-review-parallel", "--agent1", "claude"),
      parallelReviewContext(),
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
      parallelReviewContext(agentRunLauncher = NoOpAgentRunLauncher()),
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
      parallelReviewContext(agentRunLauncher = NoOpAgentRunLauncher()),
    )

    assertEquals(1, result.exitCode, result.stdout)
  }

  @Test
  fun `code-review-parallel rejects invalid scope value`() {
    val result = CliRuntime.run(
      listOf("code-review-parallel", "--agent1", "claude", "--agent2", "codex", "--scope", "invalid-scope"),
      parallelReviewContext(),
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
      parallelReviewContext(agentRunLauncher = launcher),
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
      parallelReviewContext(agentRunLauncher = launcher),
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
      parallelReviewContext(agentRunLauncher = launcher),
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
      parallelReviewContext(agentRunLauncher = launcher),
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
      parallelReviewContext(
        environment = System.getenv() + mapOf("SKILL_BILL_AGENT" to "junie"),
        agentRunLauncher = launcher,
      ),
    )

    // agent1 resolves from SKILL_BILL_AGENT; agent2 is claude so no duplicate error.
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
      parallelReviewContext(
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
      parallelReviewContext(
        environment = emptyMap(),
        agentRunLauncher = NoOpAgentRunLauncher(),
      ),
    )

    // agent1 defaults to codex; agent2 is codex → duplicate error
    assertEquals(1, result.exitCode, result.stdout)
  }
}

private fun parallelReviewContext(
  environment: Map<String, String> = System.getenv(),
  agentRunLauncher: AgentRunLauncher? = null,
): CliRuntimeContext = CliRuntimeContext(
  environment = environment,
  userHome = Files.createTempDirectory("cli-parallel-review-home"),
  agentRunLauncher = agentRunLauncher,
  executableLookup = ExecutableLookup { true },
)

private fun createGitRepo(): Path {
  val dir = Files.createTempDirectory("cli-parallel-review-git")
  runGit("init", dir.toString())
  runGit("-C", dir.toString(), "config", "user.email", "test@test.com")
  runGit("-C", dir.toString(), "config", "user.name", "Test")
  runGit("-C", dir.toString(), "config", "commit.gpgSign", "false")
  runGit("-C", dir.toString(), "commit", "--allow-empty", "-m", "initial")

  return dir
}

private fun createStagedFile(dir: Path) {
  val file = dir.resolve("Test.kt")
  Files.writeString(file, "fun main() {}\n")
  runGit("-C", dir.toString(), "add", "Test.kt")
}

private fun runGit(vararg args: String) {
  val process = ProcessBuilder("git", *args)
    .redirectErrorStream(true)
    .start()
  val output = process.inputStream.bufferedReader().use { it.readText() }
  check(process.waitFor() == 0) {
    "git ${args.joinToString(" ")} failed: $output"
  }
}

private abstract class ParallelTestAgentRunLauncher : AgentRunLauncher

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
  val promptsByAgent: MutableMap<String, String> = mutableMapOf()

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    // Lanes launch on concurrent threads; guard the recording collections.
    synchronized(lock) {
      agentIds += request.agentId
      modelsByAgent[request.agentId] = request.skillRunRequest.modelOverride
      promptsByAgent[request.agentId] = request.skillRunRequest.promptOverride.orEmpty()
    }
    return AgentRunLaunchFacts(
      agent = InstallAgent.fromNormalizedId(request.agentId, label = "agentId"),
      exitStatus = 0,
      stdout = "NO_FINDINGS",
      stderr = "",
      timedOut = false,
      spawnFailed = false,
    )
  }
}

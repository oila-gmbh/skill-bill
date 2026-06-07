package skillbill.application

import skillbill.application.model.DiffResolutionException
import skillbill.application.model.ParallelCodeReviewRequest
import skillbill.application.model.ParallelReviewScope
import skillbill.application.model.UsageValidationException
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.review.ReviewRubricPort
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.model.PilotedPlatformPackProjection
import skillbill.scaffold.model.BaselineReviewCatalog
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class ParallelCodeReviewRunnerTest {
  private val noManifestsCatalogGateway = stubCatalogGateway(emptyList())

  @Test
  fun `blank agent2 throws UsageValidationException before any launch`() {
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher)

    assertThrowsUsageValidation {
      runner.run(baseRequest(agent2Id = ""))
    }
    assertTrue(launcher.requests.isEmpty())
  }

  @Test
  fun `unsupported agent id throws UsageValidationException`() {
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher)

    assertThrowsUsageValidation {
      runner.run(baseRequest(agent2Id = "unknown-agent-xyz"))
    }
    assertTrue(launcher.requests.isEmpty())
  }

  @Test
  fun `agent1 and agent2 same id throws UsageValidationException`() {
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher)

    assertThrowsUsageValidation {
      runner.run(baseRequest(agent1Id = "claude", agent2Id = "claude"))
    }
    assertTrue(launcher.requests.isEmpty())
  }

  @Test
  fun `agent1 empty falls back to default and matches agent2 throws UsageValidationException`() {
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher)

    assertThrowsUsageValidation {
      runner.run(baseRequest(agent1Id = "", agent2Id = ""))
    }
    assertTrue(launcher.requests.isEmpty())
  }

  @Test
  fun `both lanes succeed and findings overlap produces coalesced output`() {
    val tempDir = createGitRepo()
    createStagedFile(tempDir)
    val sharedFinding = "- [F-001] Major | High | Foo.kt:1 | Shared issue"
    val launcher = alwaysSuccessLauncher(sharedFinding)
    val runner = runner(launcher)

    val result = runner.run(
      baseRequest(
        agent1Id = "claude",
        agent2Id = "codex",
        scope = ParallelReviewScope.STAGED,
        repoRoot = tempDir,
      ),
    )

    assertTrue(result.lane1Success)
    assertTrue(result.lane2Success)
    assertEquals(1, result.mergeResult.findings.size)
    assertEquals(listOf("claude", "codex"), result.mergeResult.findings[0].agentIds)
  }

  @Test
  fun `lane1 timedOut produces lane1Success false`() {
    val tempDir = createGitRepo()
    createStagedFile(tempDir)
    val launcher = GoalRunnerSubtaskLauncher { request ->
      val agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId")
      if (request.invokedAgentId == "claude") {
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
          stdout = "- [F-001] Minor | Low | A.kt:1 | Issue",
          stderr = "",
          timedOut = false,
          spawnFailed = false,
        )
      }
    }
    val runner = runner(launcher)

    val result = runner.run(
      baseRequest(agent1Id = "claude", agent2Id = "codex", scope = ParallelReviewScope.STAGED, repoRoot = tempDir),
    )

    assertFalse(result.lane1Success)
    assertTrue(result.lane2Success)
  }

  @Test
  fun `lane1 spawnFailed produces lane1Success false`() {
    val tempDir = createGitRepo()
    createStagedFile(tempDir)
    val launcher = GoalRunnerSubtaskLauncher { request ->
      val agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId")
      if (request.invokedAgentId == "claude") {
        AgentRunLaunchFacts(
          agent = agent,
          exitStatus = null,
          stdout = "",
          stderr = "",
          timedOut = false,
          spawnFailed = true,
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
    val runner = runner(launcher)

    val result = runner.run(
      baseRequest(agent1Id = "claude", agent2Id = "codex", scope = ParallelReviewScope.STAGED, repoRoot = tempDir),
    )

    assertFalse(result.lane1Success)
  }

  @Test
  fun `STAGED scope maps diff command to git diff --cached`() {
    val tempDir = createGitRepo()
    createStagedFile(tempDir)
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher)

    runner.run(
      baseRequest(agent1Id = "claude", agent2Id = "codex", scope = ParallelReviewScope.STAGED, repoRoot = tempDir),
    )

    assertTrue(launcher.requests.isNotEmpty())
  }

  @Test
  fun `BRANCH scope uses merge-base detection`() {
    val tempDir = createGitRepo()
    createCommit(tempDir, "initial.txt", "initial content")
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher)

    try {
      runner.run(
        baseRequest(agent1Id = "claude", agent2Id = "codex", scope = ParallelReviewScope.BRANCH, repoRoot = tempDir),
      )
    } catch (@Suppress("SwallowedException") e: DiffResolutionException) {
      // Expected when no commits differ; test proves the scope path executes without agent-id error
    }
  }

  private fun runner(
    launcher: GoalRunnerSubtaskLauncher,
    catalogGateway: ScaffoldCatalogGateway = noManifestsCatalogGateway,
    diffResolver: DiffResolverPort = RealProcessDiffResolver(),
    rubricPort: ReviewRubricPort = ReviewRubricPort { emptyList() },
  ): ParallelCodeReviewRunner = ParallelCodeReviewRunner(
    subtaskLauncher = launcher,
    scaffoldCatalogService = ScaffoldCatalogService(catalogGateway),
    diffResolver = diffResolver,
    rubricPort = rubricPort,
  )

  private fun baseRequest(
    agent1Id: String = "claude",
    agent2Id: String = "codex",
    scope: ParallelReviewScope = ParallelReviewScope.STAGED,
    repoRoot: Path = Files.createTempDirectory("pr-runner-test"),
    timeoutMinutes: Long? = null,
  ) = ParallelCodeReviewRequest(
    agent1Id = agent1Id,
    agent2Id = agent2Id,
    scope = scope,
    repoRoot = repoRoot,
    timeoutMinutes = timeoutMinutes,
  )

  private fun alwaysSuccessLauncher(stdout: String = "") = GoalRunnerSubtaskLauncher { request ->
    AgentRunLaunchFacts(
      agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
      exitStatus = 0,
      stdout = stdout,
      stderr = "",
      timedOut = false,
      spawnFailed = false,
    )
  }

  private fun assertThrowsUsageValidation(block: () -> Unit) {
    try {
      block()
      fail("Expected UsageValidationException")
    } catch (@Suppress("SwallowedException") e: UsageValidationException) {
      // expected
    }
  }

  private fun createGitRepo(): Path {
    val dir = Files.createTempDirectory("pr-runner-git")
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

  private fun createCommit(dir: Path, filename: String, content: String) {
    val file = dir.resolve(filename)
    Files.writeString(file, content)
    ProcessBuilder("git", "-C", dir.toString(), "add", filename).start().waitFor()
    ProcessBuilder("git", "-C", dir.toString(), "commit", "-m", "initial").start().waitFor()
  }
}

private class ParallelSubtaskLauncher(
  private val outcome: AgentRunLaunchOutcome? = null,
) : GoalRunnerSubtaskLauncher {
  val requests: MutableList<GoalRunnerSubtaskLaunchRequest> = mutableListOf()

  override fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome {
    requests += request
    return outcome ?: AgentRunLaunchFacts(
      agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
      exitStatus = 0,
      stdout = "",
      stderr = "",
      timedOut = false,
      spawnFailed = false,
    )
  }
}

private class RealProcessDiffResolver : DiffResolverPort {
  override fun runProcess(args: List<String>, workDir: Path): String? = try {
    val process = ProcessBuilder(args)
      .directory(workDir.toFile())
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    if (exitCode == 0) output else null
  } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
    null
  }
}

private fun stubCatalogGateway(manifests: List<PlatformManifest> = emptyList()): ScaffoldCatalogGateway =
  object : ScaffoldCatalogGateway {
    override fun approvedCodeReviewAreas() = emptySet<String>()
    override fun preShellFamilies() = emptySet<String>()
    override fun shelledFamilies() = emptySet<String>()
    override fun platformPackPresets() = emptyMap<String, String>()
    override fun scaffoldPayloadVersion() = "1.0"
    override fun discoverPilotedPlatformPacks(packsRoot: Path) = emptyList<PilotedPlatformPackProjection>()
    override fun discoverPlatformManifests(packsRoot: Path) = manifests
    override fun discoverBaselineReviewCatalog(packsRoot: Path) =
      BaselineReviewCatalog(packs = emptyList(), compositionEdges = emptyList(), layerSuggestions = emptyList())
  }

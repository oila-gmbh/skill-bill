package skillbill.application

import skillbill.application.model.ParallelCodeReviewRequest
import skillbill.application.model.ParallelReviewScope
import skillbill.application.model.StackDetectionException
import skillbill.application.model.UsageValidationException
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.review.ParallelReviewLaneRunner
import skillbill.ports.review.ReviewRubricPort
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ParallelReviewLaneRunRequest
import skillbill.ports.review.model.ParallelReviewLaneRunResult
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.model.PilotedPlatformPackProjection
import skillbill.scaffold.model.BaselineReviewCatalog
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

    assertTrue(result.lane1.success)
    assertTrue(result.lane2.success)
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

    assertFalse(result.lane1.success)
    assertEquals("agent timed out", result.lane1.failureReason)
    assertTrue(result.lane2.success)
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

    assertFalse(result.lane1.success)
    assertEquals("agent process failed to spawn", result.lane1.failureReason)
  }

  @Test
  fun `STAGED scope maps diff command to git diff --cached`() {
    val resolver = RecordingDiffResolver(default = "diff body")
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher, diffResolver = resolver)

    runner.run(baseRequest(agent1Id = "claude", agent2Id = "codex", scope = ParallelReviewScope.STAGED))

    assertEquals(listOf(listOf("git", "diff", "--cached")), resolver.calls)
  }

  @Test
  fun `BRANCH scope resolves merge-base then diffs base against HEAD`() {
    val resolver = RecordingDiffResolver(
      responses = mapOf(listOf("git", "merge-base", "HEAD", "main") to "base-sha\n"),
      default = "diff body",
    )
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher, diffResolver = resolver)

    runner.run(baseRequest(agent1Id = "claude", agent2Id = "codex", scope = ParallelReviewScope.BRANCH))

    assertContains(resolver.calls, listOf("git", "merge-base", "HEAD", "main"))
    assertContains(resolver.calls, listOf("git", "diff", "base-sha...HEAD"))
  }

  @Test
  fun `lane1 interrupted produces lane1Success false`() {
    val launcher = GoalRunnerSubtaskLauncher { request ->
      val agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId")
      if (request.invokedAgentId == "claude") {
        AgentRunLaunchFacts(
          agent = agent,
          exitStatus = null,
          stdout = "",
          stderr = "",
          timedOut = false,
          interrupted = true,
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
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = "diff body"))

    val result = runner.run(baseRequest(agent1Id = "claude", agent2Id = "codex", scope = ParallelReviewScope.STAGED))

    assertFalse(result.lane1.success)
    assertEquals("agent was interrupted", result.lane1.failureReason)
    assertTrue(result.lane2.success)
  }

  @Test
  fun `failed lane findings are excluded from merge result`() {
    val launcher = GoalRunnerSubtaskLauncher { request ->
      val agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId")
      if (request.invokedAgentId == "claude") {
        AgentRunLaunchFacts(
          agent = agent,
          exitStatus = null,
          stdout = "- [F-001] Major | High | Foo.kt:1 | Should not appear in merge",
          stderr = "",
          timedOut = true,
          spawnFailed = false,
        )
      } else {
        AgentRunLaunchFacts(
          agent = agent,
          exitStatus = 0,
          stdout = "- [F-001] Minor | Low | Bar.kt:2 | Lane 2 finding",
          stderr = "",
          timedOut = false,
          spawnFailed = false,
        )
      }
    }
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = "diff body"))

    val result = runner.run(baseRequest(agent1Id = "claude", agent2Id = "codex", scope = ParallelReviewScope.STAGED))

    assertFalse(result.lane1.success)
    assertTrue(result.lane2.success)
    assertEquals(1, result.mergeResult.findings.size, "failed lane findings must not appear in the merge result")
    assertEquals(listOf("codex"), result.mergeResult.findings[0].agentIds)
  }

  @Test
  fun `launcher exception produces ExecutionException outcome without killing sibling lane`() {
    val launcher = GoalRunnerSubtaskLauncher { request ->
      if (request.invokedAgentId == "claude") {
        error("internal failure in launcher")
      }
      AgentRunLaunchFacts(
        agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
        exitStatus = 0,
        stdout = "- [F-001] Minor | Low | A.kt:1 | Issue",
        stderr = "",
        timedOut = false,
        spawnFailed = false,
      )
    }
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = "diff body"))

    val result = runner.run(baseRequest(agent1Id = "claude", agent2Id = "codex", scope = ParallelReviewScope.STAGED))

    assertFalse(result.lane1.success)
    assertContains(result.lane1.failureReason.orEmpty(), "IllegalStateException")
    assertTrue(result.lane2.success)
    assertEquals(
      1,
      result.mergeResult.findings.size,
      "sibling lane finding must survive an exception in the other lane",
    )
  }

  @Test
  fun `coordinator timeout cancels blocking lane and produces failed outcome`() {
    val launcher = GoalRunnerSubtaskLauncher { request ->
      AgentRunLaunchFacts(
        agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
        exitStatus = 0,
        stdout = "",
        stderr = "",
        timedOut = false,
        spawnFailed = false,
      )
    }
    val runner = runner(
      launcher,
      diffResolver = RecordingDiffResolver(default = "diff body"),
      parallelLaneRunner = StaticParallelLaneRunner(
        ParallelReviewLaneRunResult(
          lane1 = ParallelReviewLaneOutcome(false, "", "lane timed out (cancelled by shared budget)"),
          lane2 = ParallelReviewLaneOutcome(true, ""),
        ),
      ),
    )

    val result = runner.run(
      baseRequest(agent1Id = "claude", agent2Id = "codex", scope = ParallelReviewScope.STAGED, timeout = 1.seconds),
    )

    assertFalse(result.lane1.success)
    assertContains(result.lane1.failureReason.orEmpty(), "timed out")
  }

  @Test
  fun `UnsupportedAgentRunLaunch produces failed lane outcome`() {
    val launcher = GoalRunnerSubtaskLauncher { request ->
      val agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId")
      if (request.invokedAgentId == "claude") {
        UnsupportedAgentRunLaunch(agent = agent, reason = "not configured for this repo")
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
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = "diff body"))

    val result = runner.run(baseRequest(agent1Id = "claude", agent2Id = "codex", scope = ParallelReviewScope.STAGED))

    assertFalse(result.lane1.success)
    assertContains(result.lane1.failureReason.orEmpty(), "unsupported agent")
  }

  @Test
  fun `nonzero exit status includes sanitized stderr excerpt in failure reason`() {
    val launcher = GoalRunnerSubtaskLauncher { request ->
      AgentRunLaunchFacts(
        agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
        exitStatus = 1,
        stdout = "",
        stderr = "Error: command failed with detail",
        timedOut = false,
        spawnFailed = false,
      )
    }
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = "diff body"))

    val result = runner.run(baseRequest(agent1Id = "claude", agent2Id = "codex", scope = ParallelReviewScope.STAGED))

    assertFalse(result.lane1.success)
    assertContains(result.lane1.failureReason.orEmpty(), "status 1")
    assertContains(result.lane1.failureReason.orEmpty(), "Error: command failed")
  }

  @Test
  fun `stack discovery failure surfaces as StackDetectionException`() {
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(
      launcher,
      catalogGateway = throwingCatalogGateway(),
      diffResolver = RecordingDiffResolver(default = "diff body"),
    )

    val error = assertFailsWith<StackDetectionException> {
      runner.run(baseRequest(agent1Id = "claude", agent2Id = "codex", scope = ParallelReviewScope.STAGED))
    }
    assertContains(error.message.orEmpty(), "Platform pack discovery failed")
    assertTrue(launcher.requests.isEmpty(), "lanes must not launch when stack detection fails")
  }

  private fun runner(
    launcher: GoalRunnerSubtaskLauncher,
    catalogGateway: ScaffoldCatalogGateway = noManifestsCatalogGateway,
    diffResolver: DiffResolverPort = RealProcessDiffResolver(),
    rubricPort: ReviewRubricPort = ReviewRubricPort { emptyList() },
    parallelLaneRunner: ParallelReviewLaneRunner = TestParallelLaneRunner(),
  ): ParallelCodeReviewRunner = ParallelCodeReviewRunner(
    subtaskLauncher = launcher,
    scaffoldCatalogService = ScaffoldCatalogService(catalogGateway),
    diffResolver = diffResolver,
    rubricPort = rubricPort,
    parallelLaneRunner = parallelLaneRunner,
  )

  private fun baseRequest(
    agent1Id: String = "claude",
    agent2Id: String = "codex",
    scope: ParallelReviewScope = ParallelReviewScope.STAGED,
    repoRoot: Path = Files.createTempDirectory("pr-runner-test"),
    timeout: Duration? = null,
  ) = ParallelCodeReviewRequest(
    agent1Id = agent1Id,
    agent2Id = agent2Id,
    scope = scope,
    repoRoot = repoRoot,
    timeout = timeout,
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

private class RecordingDiffResolver(
  private val responses: Map<List<String>, String?> = emptyMap(),
  private val default: String? = null,
) : DiffResolverPort {
  val calls: MutableList<List<String>> = mutableListOf()

  override fun runProcess(args: List<String>, workDir: Path): String? {
    calls += args
    return if (responses.containsKey(args)) responses[args] else default
  }
}

private class TestParallelLaneRunner : ParallelReviewLaneRunner {
  override fun runTwoLanes(request: ParallelReviewLaneRunRequest): ParallelReviewLaneRunResult =
    ParallelReviewLaneRunResult(runLane(request.lane1), runLane(request.lane2))

  private fun runLane(lane: () -> ParallelReviewLaneOutcome): ParallelReviewLaneOutcome = try {
    lane()
  } catch (e: Exception) {
    ParallelReviewLaneOutcome(
      success = false,
      rawOutput = "",
      failureReason = "lane launch threw ${e::class.simpleName}: ${e.message ?: "no detail"}",
    )
  }
}

private class StaticParallelLaneRunner(
  private val result: ParallelReviewLaneRunResult,
) : ParallelReviewLaneRunner {
  override fun runTwoLanes(request: ParallelReviewLaneRunRequest): ParallelReviewLaneRunResult = result
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

private fun throwingCatalogGateway(): ScaffoldCatalogGateway = object : ScaffoldCatalogGateway {
  override fun approvedCodeReviewAreas() = emptySet<String>()
  override fun preShellFamilies() = emptySet<String>()
  override fun shelledFamilies() = emptySet<String>()
  override fun platformPackPresets() = emptyMap<String, String>()
  override fun scaffoldPayloadVersion() = "1.0"
  override fun discoverPilotedPlatformPacks(packsRoot: Path) = emptyList<PilotedPlatformPackProjection>()
  override fun discoverPlatformManifests(packsRoot: Path): List<PlatformManifest> =
    error("corrupt platform.yaml in $packsRoot")
  override fun discoverBaselineReviewCatalog(packsRoot: Path) =
    BaselineReviewCatalog(packs = emptyList(), compositionEdges = emptyList(), layerSuggestions = emptyList())
}

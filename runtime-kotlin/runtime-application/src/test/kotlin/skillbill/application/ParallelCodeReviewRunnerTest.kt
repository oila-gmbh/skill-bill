package skillbill.application

import skillbill.application.model.ParallelCodeReviewRequest
import skillbill.application.model.ParallelReviewScope
import skillbill.application.model.StackDetectionException
import skillbill.application.model.UsageValidationException
import skillbill.application.review.DelegatedReviewExecutionBroker
import skillbill.application.review.DelegatedReviewLaunchBroker
import skillbill.application.review.DelegatedReviewWorkerLauncher
import skillbill.application.review.ParallelCodeReviewRunner
import skillbill.application.scaffold.ScaffoldCatalogService
import skillbill.application.workflow.repoRoot
import skillbill.config.model.RepoLocalConfig
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.ConversationIsolation
import skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigResult
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.review.NativeReviewWorkerLauncher
import skillbill.ports.review.ParallelReviewLaneRunner
import skillbill.ports.review.ReviewEvidenceBroker
import skillbill.ports.review.ReviewEvidenceBrokerFactory
import skillbill.ports.review.ReviewRubricResolver
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ParallelReviewLaneRunRequest
import skillbill.ports.review.model.ParallelReviewLaneRunResult
import skillbill.ports.review.model.ResolvedReviewRubric
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewToolCall
import skillbill.ports.review.model.ReviewToolCallResult
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.model.PilotedPlatformPackProjection
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewBudgetEvaluator
import skillbill.review.context.model.ReviewBudgetOutcome
import skillbill.review.context.model.ReviewLaneIdentity
import skillbill.scaffold.model.BaselineReviewCatalog
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.RoutingSignals
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
  fun `supplied exact diff bypasses branch-scope resolution for both lanes`() {
    val resolver = RecordingDiffResolver(default = "unexpected branch diff")
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(
      launcher,
      catalogGateway = stubCatalogGateway(listOf(platformManifest("kotlin", listOf("*.kt")))),
      diffResolver = resolver,
    )
    val exactDiff = "diff --git a/Child.kt b/Child.kt\n+++ b/Child.kt\n+owned change\n"

    runner.run(baseRequest(scope = ParallelReviewScope.BRANCH).copy(suppliedDiff = exactDiff))

    assertTrue(resolver.calls.isEmpty())
    assertEquals(2, launcher.requests.size)
    launcher.requests.forEach { request ->
      val prompt = request.skillRunRequest.promptOverride.orEmpty()
      assertContains(prompt, "assigned_paths:")
      assertContains(prompt, "- Child.kt")
      assertContains(prompt, "specialist_contract:")
      assertContains(prompt, "rubric:")
      assertContains(prompt, "evidence_surface:")
      assertContains(prompt, "brokered_evidence:")
      assertContains(prompt, "brokered:Child.kt")
      assertEquals(ConversationIsolation.NONE, request.skillRunRequest.conversationIsolation)
      assertTrue(request.skillRunRequest.reviewEvidenceBroker != null)
      assertContains(prompt, "assignment_digest:")
      assertFalse(prompt.contains("fork_turns:"))
      assertFalse(prompt.contains("## Specialist:"), "flattened specialist rubric bodies must stay out of lane prompts")
      assertFalse(prompt.contains("Apply all of the following specialist review rubrics"))
    }
  }

  @Test
  fun `provider usage is preserved in lane status`() {
    val launcher = GoalRunnerSubtaskLauncher { request ->
      AgentRunLaunchFacts(
        agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
        exitStatus = 0,
        stdout = "",
        stderr = "",
        timedOut = false,
        spawnFailed = false,
        inputTokens = 100,
        cachedInputTokens = 40,
        outputTokens = 10,
        totalTokens = 110,
      )
    }

    val result = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))
      .run(baseRequest(scope = ParallelReviewScope.STAGED))

    assertEquals(70, result.lane1.tokenUsage?.freshTokenApproximation)
    assertEquals(110, result.lane2.tokenUsage?.totalTokens)
    assertEquals("claude", result.lane1.accounting?.lane)
    assertEquals(1, result.lane1.accounting?.modelTurns)
    assertEquals(0, result.lane1.accounting?.resultBytes)
  }

  @Test
  fun `excessive lane result terminates with typed budget outcome`() {
    val runner = runner(
      alwaysSuccessLauncher("x".repeat(65_537)),
      diffResolver = RecordingDiffResolver(default = diffFor("A.kt")),
    )

    val result = runner.run(baseRequest(scope = ParallelReviewScope.STAGED))

    assertFalse(result.lane1.success)
    assertContains(result.lane1.failureReason.orEmpty(), "review_context_budget_exceeded")
    assertEquals("review_context_budget_exceeded", result.lane1.budgetOutcome?.type)
    assertTrue(result.mergeResult.findings.isEmpty())
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

  @Test
  fun `stack detection matches wildcard configuration signals`() {
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(
      launcher,
      catalogGateway = stubCatalogGateway(listOf(platformManifest("typescript", listOf("tsconfig.*.json")))),
      diffResolver = RecordingDiffResolver(default = diffFor("tsconfig.base.json")),
    )

    runner.run(baseRequest(scope = ParallelReviewScope.STAGED))

    assertEquals(2, launcher.requests.size)
  }

  @Test
  fun `detected manifest selects the governed baseline rubric before lane launch`() {
    val launcher = ParallelSubtaskLauncher()
    var resolvedSlug: String? = null
    val runner = runner(
      launcher,
      catalogGateway = stubCatalogGateway(listOf(platformManifest("kotlin", listOf("*.kt")))),
      diffResolver = RecordingDiffResolver(default = diffFor("src/Main.kt")),
      rubricResolver = ReviewRubricResolver { manifest ->
        resolvedSlug = manifest?.slug
        ResolvedReviewRubric("bill-kotlin-code-review", "manifest-owned kotlin rubric")
      },
    )

    runner.run(baseRequest(scope = ParallelReviewScope.STAGED))

    assertEquals("kotlin", resolvedSlug)
    launcher.requests.forEach { request ->
      assertContains(request.skillRunRequest.promptOverride.orEmpty(), "manifest-owned kotlin rubric")
    }
  }

  @Test
  fun `stack detection excludes generated dependency and build paths`() {
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(
      launcher,
      catalogGateway = stubCatalogGateway(listOf(platformManifest("typescript", listOf("*.ts", ".ts")))),
      diffResolver = RecordingDiffResolver(
        default = listOf(
          "node_modules/library/index.ts",
          "dist/app.ts",
          "build/bundle.ts",
          "coverage/report.ts",
          "src/generated/client.ts",
          "src/api/client.d.ts",
        ).joinToString("\n", transform = ::diffFor),
      ),
    )

    runner.run(baseRequest(scope = ParallelReviewScope.STAGED))

    assertFalse(
      launcher.requests.any { request ->
        request.skillRunRequest.promptOverride.orEmpty().contains("dominant stack is typescript")
      },
    )
  }

  private fun runner(
    launcher: GoalRunnerSubtaskLauncher,
    catalogGateway: ScaffoldCatalogGateway = noManifestsCatalogGateway,
    diffResolver: DiffResolverPort = RealProcessDiffResolver(),
    parallelLaneRunner: ParallelReviewLaneRunner = TestParallelLaneRunner(),
    rubricResolver: ReviewRubricResolver = ReviewRubricResolver {
      ResolvedReviewRubric("parallel-code-review", "governed generic rubric")
    },
  ): ParallelCodeReviewRunner = ParallelCodeReviewRunner(
    delegatedReviewExecutionBroker = DelegatedReviewExecutionBroker(
      DelegatedReviewLaunchBroker(
        evidenceBrokerFactory = ReviewEvidenceBrokerFactory(::TestReviewEvidenceBroker),
        isolationResolver = { agentId ->
          ReviewLaunchIsolationStrategy.FRESH_PROCESS
        },
      ),
      DelegatedReviewWorkerLauncher(
        NativeReviewWorkerLauncher { request ->
          request.operations.modelTurn()?.let {
            return@NativeReviewWorkerLauncher AgentRunLaunchFacts(
              agent = InstallAgent.fromNormalizedId(request.agentId),
              exitStatus = null,
              stdout = "",
              stderr = "review budget terminated before provider execution",
              timedOut = false,
              spawnFailed = false,
            )
          }
          launcher.launch(
            GoalRunnerSubtaskLaunchRequest(
              invokedAgentId = request.agentId,
              configuredAgentOverrideId = null,
              skillRunRequest = skillbill.ports.agentrun.model.SkillRunRequest(
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
        },
      ),
    ),
    scaffoldCatalogService = ScaffoldCatalogService(catalogGateway),
    diffResolver = diffResolver,
    parallelLaneRunner = parallelLaneRunner,
    repoLocalConfig = object : RepoLocalConfigPort {
      override fun readRepoLocalConfig(request: skillbill.ports.config.model.ReadRepoLocalConfigRequest) =
        ReadRepoLocalConfigResult(RepoLocalConfig.defaults())
    },
    reviewContextEnvelopeValidator = object : skillbill.review.context.ReviewContextEnvelopeValidator {
      override fun validate(envelope: Map<String, Any?>, sourceLabel: String) = Unit
    },
    reviewRubricResolver = rubricResolver,
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

private fun platformManifest(slug: String, strongSignals: List<String>) = PlatformManifest(
  slug = slug,
  packRoot = Path.of("platform-packs/$slug"),
  contractVersion = "1.2",
  routingSignals = RoutingSignals(strong = strongSignals, tieBreakers = emptyList()),
  declaredCodeReviewAreas = emptyList(),
  declaredFiles = DeclaredFiles(baseline = Path.of("content.md"), areas = emptyMap()),
  areaMetadata = emptyMap(),
)

private fun diffFor(path: String): String = "+++ b/$path"

private class TestReviewEvidenceBroker(
  private val binding: ReviewEvidenceBrokerBinding,
) : ReviewEvidenceBroker {
  private val identity = ReviewLaneIdentity.of(binding.assignment)
  private var resultBytes = 0L
  private var modelTurns = 0
  private var terminal: ReviewBudgetOutcome? = null

  override fun readBatch(request: ReviewEvidenceBatchRequest) = ReviewEvidenceBatchResult(
    request.requests.map { ReviewEvidenceResult("brokered:${it.path}", 0, 0, 0) },
    0,
    emptyList(),
    terminal,
  )

  override fun recordToolCall(call: ReviewToolCall) = ReviewToolCallResult(budgetExceeded = terminal)

  override fun recordModelTurn(): ReviewBudgetOutcome? {
    modelTurns += 1
    return terminal ?: ReviewBudgetEvaluator.exceededOrNull(
      identity,
      "model_turns",
      binding.budget.maxSpecialistModelTurns.toLong(),
      modelTurns.toLong(),
    ).also { terminal = it }
  }

  override fun validateLaneResult(result: String): ReviewBudgetOutcome? {
    resultBytes = maxOf(resultBytes, result.toByteArray().size.toLong())
    return ReviewBudgetEvaluator.laneResultOutcome(identity, binding.budget, resultBytes).also { terminal = it }
  }

  override fun observeLaneResultChunk(chunk: String): ReviewBudgetOutcome? {
    resultBytes += chunk.toByteArray().size
    return ReviewBudgetEvaluator.laneResultOutcome(identity, binding.budget, resultBytes).also { terminal = it }
  }

  override fun evaluateProviderUsage(usage: ProviderTokenUsage, enforceable: Boolean): ReviewBudgetOutcome? =
    terminal ?: ReviewBudgetEvaluator.providerUsageOutcome(
      identity,
      binding.budget.providerTokenThresholds,
      usage,
      enforceable,
    ).also { terminal = it }

  override fun accounting() = ReviewLaneAccounting(
    lane = binding.assignment.lane,
    evidenceBytes = 0,
    expansions = emptyList(),
    toolCalls = 0,
    modelTurns = modelTurns,
    resultBytes = resultBytes,
    terminalOutcome = terminal,
  )

  override fun terminalOutcome(): ReviewBudgetOutcome? = terminal
}

package skillbill.application

import skillbill.application.model.ParallelCodeReviewRequest
import skillbill.application.model.ParallelReviewScope
import skillbill.application.model.StackDetectionException
import skillbill.application.model.UsageValidationException
import skillbill.application.review.ParallelCodeReviewRunner
import skillbill.application.workflow.repoRoot
import skillbill.config.model.RepoLocalConfig
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigResult
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.review.ParallelReviewLaneRunner
import skillbill.ports.review.ReviewRubricResolver
import skillbill.ports.review.ReviewSpecialistContractProvider
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ParallelReviewLaneRunRequest
import skillbill.ports.review.model.ParallelReviewLaneRunResult
import skillbill.ports.review.model.ResolvedReviewRubric
import skillbill.ports.scaffold.InstalledPlatformPackCatalogPort
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.model.PilotedPlatformPackProjection
import skillbill.review.ParallelReviewFindingParser
import skillbill.review.context.model.REVIEW_ROUTING_ANALYSIS_PAIRS_BUDGET
import skillbill.review.context.model.ReviewContextBudgetExceededException
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.model.ReviewRunLane
import skillbill.scaffold.model.BaselineReviewCatalog
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.ReviewLaneCondition
import skillbill.scaffold.model.RoutingSignals
import skillbill.workflow.model.CodeReviewExecutionMode
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ParallelCodeReviewRunnerTest {
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
    val sharedFinding = "- [F-001] Major | High | path=\"Test.kt\" | line=1 | Shared issue"
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
  fun `delegated review obtains specialist contract independently of reviewed checkout`() {
    val reviewedRepo = createGitRepo()
    createStagedFile(reviewedRepo)
    val unrelatedWorkingDirectory = Files.createTempDirectory("unrelated-working-directory")
    val originalWorkingDirectory = System.getProperty("user.dir")
    try {
      System.setProperty("user.dir", unrelatedWorkingDirectory.toString())

      val result = runner(alwaysSuccessLauncher()).run(
        baseRequest(scope = ParallelReviewScope.STAGED, repoRoot = reviewedRepo),
      )

      assertTrue(result.lane1.success)
      assertTrue(result.lane2.success)
    } finally {
      System.setProperty("user.dir", originalWorkingDirectory)
    }
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
          stdout = "- [F-001] Minor | Low | path=\"Test.kt\" | line=1 | Issue",
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
    assertTrue(result.lane2.success, "An independent sibling lane survives lane 1's timeout.")
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
    val resolver = RecordingDiffResolver(
      responses = mapOf(listOf("git", "rev-parse", "--verify", "HEAD^{commit}") to "head-sha\n"),
      default = diffFor("A.kt"),
    )
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher, diffResolver = resolver)

    runner.run(baseRequest(agent1Id = "claude", agent2Id = "codex", scope = ParallelReviewScope.STAGED))

    assertContains(resolver.calls, listOf("git", "diff", "--cached"))
  }

  @Test
  fun `BRANCH scope resolves merge-base then diffs the canonical base against the canonical head`() {
    val resolver = RecordingDiffResolver(
      responses = mapOf(
        listOf("git", "rev-parse", "--verify", "HEAD^{commit}") to "head-sha\n",
        listOf("git", "merge-base", "HEAD", "main") to "base-sha\n",
        listOf("git", "rev-list", "--first-parent", "--reverse", "base-sha..head-sha") to "",
      ),
      default = diffFor("A.kt"),
    )
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher, diffResolver = resolver)

    runner.run(
      baseRequest(agent1Id = "claude", agent2Id = "codex", scope = ParallelReviewScope.BRANCH).detectingRevisions(),
    )

    assertContains(resolver.calls, listOf("git", "merge-base", "HEAD", "main"))
    assertContains(resolver.calls, listOf("git", "diff", "base-sha", "head-sha"))
  }

  // AC-001: a PR review spans its own base branch instead of collapsing to HEAD..HEAD.
  @Test
  fun `PR scope resolves the pull request base and enumerates its commit range`() {
    val resolver = RecordingDiffResolver(
      responses = mapOf(
        listOf("git", "rev-parse", "--verify", "HEAD^{commit}") to "head-sha\n",
        listOf("gh", "pr", "view", "--json", "baseRefOid", "--jq", ".baseRefOid") to "pr-base-oid\n",
        listOf("git", "merge-base", "HEAD", "pr-base-oid") to "base-sha\n",
        listOf("git", "rev-list", "--first-parent", "--reverse", "base-sha..head-sha") to "",
      ),
      default = diffFor("A.kt"),
    )
    val runner = runner(ParallelSubtaskLauncher(), diffResolver = resolver)

    runner.run(
      baseRequest(agent1Id = "claude", agent2Id = "codex", scope = ParallelReviewScope.PR).detectingRevisions(),
    )

    assertContains(resolver.calls, listOf("gh", "pr", "view", "--json", "baseRefOid", "--jq", ".baseRefOid"))
    assertContains(
      resolver.calls,
      listOf("git", "rev-list", "--first-parent", "--reverse", "base-sha..head-sha"),
    )
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
      assertContains(prompt, "Resolved execution mode: inline")
      assertContains(prompt, "Owned paths: \"Child.kt\"")
      assertContains(prompt, "## Assigned bundle:")
      assertContains(prompt, "\"Child.kt\"")
      assertContains(prompt, "+owned change")
      assertContains(prompt, "Use the assigned bundle evidence below as authoritative")
      assertFalse(prompt.contains("unexpected branch diff"), "the supplied diff must replace branch resolution")
      assertEquals(null, request.skillRunRequest.nativeReviewWorkerName)
    }
  }

  @Test
  fun `review prompt asks for the commit attribution segment the parser reads`() {
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

    runner.run(baseRequest(scope = ParallelReviewScope.STAGED))

    assertTrue(launcher.requests.isNotEmpty())
    launcher.requests.forEach { request ->
      val prompt = request.skillRunRequest.promptOverride.orEmpty()
      assertContains(prompt, "commits=<sha>[,<sha>] | path=<JSON string>")
      assertContains(prompt, "required whenever a finding relates code from more than one assigned commit")
    }
    val parsed = ParallelReviewFindingParser.parse(
      "[F-001] Major | High | specialist=generic-security | commits=aaa111,bbb222 | " +
        "path=\"A.kt\" | line=4 | contract introduced then changed",
    )
    assertEquals(listOf("aaa111", "bbb222"), parsed.single().commitShas)
  }

  @Test
  fun `inline mode bypasses delegated specialist workers`() {
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

    runner.run(
      baseRequest(scope = ParallelReviewScope.STAGED).copy(codeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    assertEquals(2, launcher.requests.size)
    launcher.requests.forEach { request ->
      assertEquals(null, request.skillRunRequest.reviewEvidenceBroker)
      assertContains(request.skillRunRequest.promptOverride.orEmpty(), "bill-code-review mode:inline")
      assertContains(request.skillRunRequest.promptOverride.orEmpty(), "do not launch specialists")
      assertContains(request.skillRunRequest.promptOverride.orEmpty(), "governed generic rubric")
      assertContains(request.skillRunRequest.promptOverride.orEmpty(), "paths=\"A.kt\"")
    }
  }

  @Test
  fun `inline mode issues exactly one review prompt per lane and launches no specialist`() {
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

    runner.run(
      baseRequest(scope = ParallelReviewScope.STAGED).copy(codeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    assertEquals(
      listOf("claude", "codex"),
      launcher.requests.map { it.invokedAgentId }.sorted(),
      "Inline runs one prompt for the primary lane and one for the parallel-review lane, nothing else.",
    )
    launcher.requests.forEach { request ->
      assertEquals(
        null,
        request.skillRunRequest.nativeReviewWorkerName,
        "An inline prompt is never a specialist worker launch.",
      )
      assertContains(request.skillRunRequest.promptOverride.orEmpty(), "Resolved execution mode: inline")
      assertContains(
        request.skillRunRequest.promptOverride.orEmpty(),
        "Run exactly one bill-code-review mode:inline review prompt in this context.",
      )
    }
  }

  @Test
  fun `an omitted mode resolves to the inline tier on both lanes`() {
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

    // The request default is what an omitted --execution-mode resolves to.
    runner.run(
      baseRequest(scope = ParallelReviewScope.STAGED).copy(codeReviewMode = CodeReviewExecutionMode.DEFAULT),
    )

    assertEquals(2, launcher.requests.size)
    launcher.requests.forEach { request ->
      val prompt = request.skillRunRequest.promptOverride.orEmpty()
      assertContains(prompt, "bill-code-review mode:inline")
      assertContains(prompt, "do not launch specialists")
      assertFalse(prompt.contains("Launch one specialist worker per resolved rubric"))
    }
  }

  @Test
  fun `the parallel-review lane inherits the primary lane resolved mode`() {
    listOf(
      CodeReviewExecutionMode.INLINE to "inline",
      CodeReviewExecutionMode.DELEGATED to "delegated",
      CodeReviewExecutionMode.AUTO to "inline",
    ).forEach { (requested, expectedWire) ->
      val launcher = ParallelSubtaskLauncher()
      val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

      runner.run(
        baseRequest(scope = ParallelReviewScope.STAGED).copy(codeReviewMode = requested),
      )

      assertEquals(2, launcher.requests.size, "$requested must run a primary lane and a second lane.")
      launcher.requests.forEach { request ->
        assertContains(
          request.skillRunRequest.promptOverride.orEmpty(),
          "Resolved execution mode: $expectedWire",
        )
      }
    }
  }

  @Test
  fun `inline mode accounting carries the parent prompt and stdout as one specialist-free turn`() {
    val launcher = GoalRunnerSubtaskLauncher { request ->
      AgentRunLaunchFacts(
        agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
        exitStatus = 0,
        stdout = "- [F-001] Major | High | path=\"A.kt\" | line=1 | Inline finding",
        stderr = "",
        timedOut = false,
        spawnFailed = false,
      )
    }
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

    val result = runner.run(
      baseRequest(scope = ParallelReviewScope.STAGED).copy(codeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    assertTrue(result.lane1.success)
    val accounting = assertNotNull(result.lane1.accounting)
    assertEquals("completed", accounting.terminalStatus)
    assertEquals(1, accounting.modelTurns, "An inline lane is exactly one parent turn, never a specialist child.")
    assertEquals(0L, accounting.evidenceBytes, "Inline mode never brokers evidence through a child worker.")
    assertTrue(accounting.launchBytes > 0, "The rendered parent prompt must be measured as launch bytes.")
    assertEquals(
      "- [F-001] Major | High | path=\"A.kt\" | line=1 | Inline finding".toByteArray().size.toLong(),
      accounting.resultBytes,
    )
  }

  @Test
  fun `inline mode accounting reports unsupported_provider without a session turn`() {
    val launcher = GoalRunnerSubtaskLauncher { request ->
      UnsupportedAgentRunLaunch(
        agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
        reason = "not configured for this repo",
      )
    }
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

    val result = runner.run(
      baseRequest(scope = ParallelReviewScope.STAGED).copy(codeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    assertFalse(result.lane1.success)
    assertContains(result.lane1.failureReason.orEmpty(), "unsupported agent")
    val accounting = assertNotNull(result.lane1.accounting)
    assertEquals("unsupported_provider", accounting.terminalStatus)
    assertEquals(0L, accounting.resultBytes, "No session ran, so there is no result to measure.")
  }

  @Test
  fun `delegated routing launches one rubric per non-empty selected specialist`() {
    val launcher = ParallelSubtaskLauncher()
    val architecture = ResolvedReviewRubric(
      "bill-kotlin-code-review-architecture",
      "architecture specialist rubric",
      area = "architecture",
    )
    val testing = ResolvedReviewRubric(
      "bill-kotlin-code-review-testing",
      "testing specialist rubric",
      area = "testing",
    )
    val runner = runner(
      launcher,
      catalogGateway = stubCatalogGateway(listOf(platformManifest("kotlin", listOf("*.kt")))),
      diffResolver = RecordingDiffResolver(default = diffFor("src/Main.kt")),
      rubricResolver = ReviewRubricResolver {
        ResolvedReviewRubric(
          "bill-kotlin-code-review",
          "parent routing rubric",
          specialists = listOf(architecture, testing),
        )
      },
    )

    runner.run(baseRequest(scope = ParallelReviewScope.STAGED))

    assertEquals(2, launcher.requests.size, "empty testing lanes must be dropped for both review agents")
    launcher.requests.forEach { request ->
      val prompt = request.skillRunRequest.promptOverride.orEmpty()
      assertContains(prompt, "architecture specialist rubric")
      assertFalse(prompt.contains("testing specialist rubric"))
      assertFalse(prompt.contains("parent routing rubric"))
    }
  }

  @Test
  fun `a failed specialist does not discard a successful sibling specialist's findings`() {
    val architectureRubric = "architecture specialist rubric"
    val testingRubric = "testing specialist rubric"
    val launcher = GoalRunnerSubtaskLauncher { request ->
      val agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId")
      val prompt = request.skillRunRequest.promptOverride.orEmpty()
      if (prompt.contains(architectureRubric)) {
        AgentRunLaunchFacts(
          agent = agent,
          exitStatus = 1,
          stdout = "",
          stderr = "boom",
          timedOut = false,
          spawnFailed = false,
        )
      } else {
        AgentRunLaunchFacts(
          agent = agent,
          exitStatus = 0,
          stdout = "- [F-001] Major | High | path=\"src/FooTest.kt\" | line=1 | Testing issue",
          stderr = "",
          timedOut = false,
          spawnFailed = false,
        )
      }
    }
    val runner = runner(
      launcher,
      catalogGateway = stubCatalogGateway(listOf(platformManifest("kotlin", listOf("*.kt")))),
      diffResolver = RecordingDiffResolver(default = diffFor("src/Main.kt") + "\n" + diffFor("src/FooTest.kt")),
      rubricResolver = ReviewRubricResolver {
        ResolvedReviewRubric(
          "bill-kotlin-code-review",
          "parent routing rubric",
          specialists = listOf(
            ResolvedReviewRubric("bill-kotlin-code-review-architecture", architectureRubric, area = "architecture"),
            ResolvedReviewRubric("bill-kotlin-code-review-testing", testingRubric, area = "testing"),
          ),
        )
      },
    )

    val result = runner.run(baseRequest(scope = ParallelReviewScope.STAGED))

    assertFalse(result.lane1.success, "The lane as a whole failed because its architecture specialist failed.")
    assertFalse(result.lane2.success, "Incomplete delegated worker coverage must block the aggregate result.")
    assertTrue(result.mergeResult.findings.isEmpty(), "Blocked aggregation must not publish partial findings.")
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
}

class ParallelCodeReviewRunnerFailureTest {
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
          stdout = "- [F-001] Minor | Low | path=\"A.kt\" | line=1 | Issue",
          stderr = "",
          timedOut = false,
          spawnFailed = false,
        )
      }
    }
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

    val result = runner.run(baseRequest(agent1Id = "claude", agent2Id = "codex", scope = ParallelReviewScope.STAGED))

    assertFalse(result.lane1.success)
    assertEquals("agent was interrupted", result.lane1.failureReason)
    assertTrue(result.lane2.success, "An independent sibling lane survives lane 1's interruption.")
  }

  @Test
  fun `failed lane findings are excluded from merge result`() {
    val launcher = GoalRunnerSubtaskLauncher { request ->
      val agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId")
      if (request.invokedAgentId == "claude") {
        AgentRunLaunchFacts(
          agent = agent,
          exitStatus = null,
          stdout = "- [F-001] Major | High | path=\"A.kt\" | line=1 | Should not appear in merge",
          stderr = "",
          timedOut = true,
          spawnFailed = false,
        )
      } else {
        AgentRunLaunchFacts(
          agent = agent,
          exitStatus = 0,
          stdout = "- [F-001] Minor | Low | path=\"A.kt\" | line=2 | Lane 2 finding",
          stderr = "",
          timedOut = false,
          spawnFailed = false,
        )
      }
    }
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

    val result = runner.run(baseRequest(agent1Id = "claude", agent2Id = "codex", scope = ParallelReviewScope.STAGED))

    assertFalse(result.lane1.success)
    assertTrue(result.lane2.success)
    assertTrue(
      result.mergeResult.findings.none { it.description.contains("Should not appear in merge") },
      "A failed lane's own output must not reach the merge.",
    )
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
        stdout = "- [F-001] Minor | Low | path=\"A.kt\" | line=1 | Issue",
        stderr = "",
        timedOut = false,
        spawnFailed = false,
      )
    }
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

    val result = runner.run(baseRequest(agent1Id = "claude", agent2Id = "codex", scope = ParallelReviewScope.STAGED))

    assertFalse(result.lane1.success)
    assertContains(result.lane1.failureReason.orEmpty(), "IllegalStateException")
    assertTrue(result.lane2.success, "A launcher exception in lane 1 must not kill the sibling lane.")
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
    val runner = runnerWithParallelLane(
      launcher,
      RecordingDiffResolver(default = diffFor("A.kt")),
      StaticParallelLaneRunner(
        ParallelReviewLaneRunResult(
          lane1 = ParallelReviewLaneOutcome(false, "", "lane timed out (cancelled by shared budget)"),
          lane2 = ParallelReviewLaneOutcome(true, ""),
        ),
      ),
    )

    val result = runner.run(
      baseRequest(agent1Id = "claude", agent2Id = "codex", scope = ParallelReviewScope.STAGED, timeout = 1.seconds)
        .copy(codeReviewMode = CodeReviewExecutionMode.INLINE),
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
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

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
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

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
      diffResolver = RecordingDiffResolver(default = diffFor("A.kt")),
    )

    val error = assertFailsWith<StackDetectionException> {
      runner.run(baseRequest(agent1Id = "claude", agent2Id = "codex", scope = ParallelReviewScope.STAGED))
    }
    assertContains(error.message.orEmpty(), "Installed platform pack discovery failed")
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
  fun `unsupported delta with installed concrete pack uses horizontal base rubric`() {
    val launcher = ParallelSubtaskLauncher()
    var resolvedSlug: String? = "unresolved"
    val runner = createRunner(
      launcher,
      RunnerFixtureConfig(
        diffResolver = RecordingDiffResolver(default = diffFor("README.md")),
        rubricResolver = ReviewRubricResolver { manifest ->
          resolvedSlug = manifest?.slug
          ResolvedReviewRubric("parallel-code-review", "horizontal base rubric")
        },
        catalogGateway = stubCatalogGateway(listOf(platformManifest("typescript", listOf("*.ts", ".ts")))),
      ),
    )

    runner.run(baseRequest(scope = ParallelReviewScope.STAGED))

    assertEquals(null, resolvedSlug)
    assertEquals(2, launcher.requests.size)
    launcher.requests.forEach { request ->
      assertContains(request.skillRunRequest.promptOverride.orEmpty(), "horizontal base rubric")
    }
  }

  // SKILL-136 subtask 5 AC-001/AC-002: a runtime-launched review is attributed from the launch plan
  // it resolved, not from the review text it later produces.
  @Test
  fun `runtime launched review records one lane row per planned lane from the plan`() {
    val database = RecordingReviewDatabase()
    val runner = createRunner(
      ParallelSubtaskLauncher(),
      RunnerFixtureConfig(
        catalogGateway = stubCatalogGateway(listOf(platformManifest("kotlin", listOf("*.kt")))),
        diffResolver = RecordingDiffResolver(default = diffFor("src/Main.kt")),
        database = database,
      ),
    )
    val request = baseRequest(scope = ParallelReviewScope.STAGED)

    runner.run(request)

    val (runId, lanes) = database.laneWrites.last()
    assertEquals(request.reviewRunId, runId)
    assertTrue(lanes.isNotEmpty(), "A runtime-launched review must record the lanes it planned.")
    assertTrue(lanes.all { it.resolutionState == "resolved" })
    assertTrue(lanes.all { it.packSlug.isNotBlank() && it.area.isNotBlank() })
    assertEquals(lanes.map { it.laneSkillName }.distinct().size, lanes.size)
    assertEquals(lanes.map { it.orderIndex }.sorted(), lanes.map { it.orderIndex })
    assertTrue(
      lanes.all { it.reviewDisposition == "complete" },
      "Successful parallel pass must persist complete disposition for every planned lane.",
    )
    assertTrue(database.laneWrites.size >= 2, "Plan recording and disposition finalization must both write.")
  }

  // AC-003: the lane that produced a finding is recorded from the runtime's own merge result, so it
  // never depends on an agent reproducing a provenance annotation in the review text it emits.
  @Test
  fun `runtime launched review records the producing lane of every merged finding`() {
    val database = RecordingReviewDatabase()
    val runner = createRunner(
      ParallelSubtaskLauncher(
        outcome = AgentRunLaunchFacts(
          agent = InstallAgent.fromNormalizedId("claude", label = "agentId"),
          exitStatus = 0,
          stdout = "[F-001] Major | High | path=\"src/Main.kt\" | line=3 | Transaction is not rolled back.",
          stderr = "",
          timedOut = false,
          spawnFailed = false,
        ),
      ),
      RunnerFixtureConfig(
        catalogGateway = stubCatalogGateway(listOf(platformManifest("kotlin", listOf("*.kt")))),
        diffResolver = RecordingDiffResolver(default = diffFor("src/Main.kt")),
        database = database,
      ),
    )
    val request = baseRequest(scope = ParallelReviewScope.STAGED)

    runner.run(request)

    val (runId, attribution) = database.findingLaneWrites.single()
    assertEquals(request.reviewRunId, runId)
    assertEquals(
      database.laneWrites.last().second.map { it.laneSkillName }.toSet(),
      attribution.values.toSet(),
      "Attribution must name a lane the run actually planned.",
    )
    assertEquals(setOf("F-001"), attribution.keys)
  }

  @Test
  fun `stack detection excludes generated dependency and build paths`() {
    val launcher = ParallelSubtaskLauncher()
    val runner = runner(
      launcher,
      catalogGateway = stubCatalogGateway(
        listOf(platformManifest("typescript", listOf("*.ts", ".ts")), fallbackManifest()),
      ),
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

  // AC-004, AC-007, AC-008: synthetic staged/unstaged scopes keep required coverage and drop clear
  // irrelevant optional specialists before launch.
  @Test
  fun `sparse routing on a staged UI-only diff drops security and keeps the required baseline`() {
    val launcher = ParallelSubtaskLauncher()
    val pack = sparsePlatformManifest(
      requiredArea = "architecture",
      pathAreas = mapOf(
        "ui" to listOf("ui/"),
        "security" to listOf("auth/"),
      ),
    )
    val runner = createRunner(
      launcher,
      RunnerFixtureConfig(
        catalogGateway = stubCatalogGateway(listOf(pack)),
        diffResolver = RecordingDiffResolver(default = diffFor("ui/Screen.kt")),
      ),
    )

    runner.run(baseRequest(scope = ParallelReviewScope.STAGED))

    val rubrics = launcher.requests.flatMap { request ->
      Regex("## Resolved rubric: (\\S+)")
        .findAll(request.skillRunRequest.promptOverride.orEmpty())
        .map { it.groupValues[1] }
        .toList()
    }.toSet()
    assertTrue("bill-kotlin-code-review-architecture" in rubrics, rubrics.toString())
    assertTrue("bill-kotlin-code-review-ui" in rubrics, rubrics.toString())
    assertFalse("bill-kotlin-code-review-security" in rubrics, rubrics.toString())
  }

  @Test
  fun `sparse routing on an unstaged UI-only diff matches the staged lane selection`() {
    val pack = sparsePlatformManifest(
      requiredArea = "architecture",
      pathAreas = mapOf(
        "ui" to listOf("ui/"),
        "security" to listOf("auth/"),
      ),
    )
    fun launchedRubrics(scope: ParallelReviewScope): Set<String> {
      val launcher = ParallelSubtaskLauncher()
      createRunner(
        launcher,
        RunnerFixtureConfig(
          catalogGateway = stubCatalogGateway(listOf(pack)),
          diffResolver = RecordingDiffResolver(default = diffFor("ui/Screen.kt")),
        ),
      ).run(baseRequest(scope = scope))
      return launcher.requests.flatMap { request ->
        Regex("## Resolved rubric: (\\S+)")
          .findAll(request.skillRunRequest.promptOverride.orEmpty())
          .map { it.groupValues[1] }
          .toList()
      }.toSet()
    }

    assertEquals(launchedRubrics(ParallelReviewScope.STAGED), launchedRubrics(ParallelReviewScope.UNSTAGED))
  }

  // AC-010
  @Test
  fun `a parent routing-analysis budget breach fails loudly before launch`() {
    val launcher = ParallelSubtaskLauncher()
    val pack = sparsePlatformManifest(
      requiredArea = "architecture",
      pathAreas = mapOf(
        "ui" to listOf("ui/"),
        "security" to listOf("auth/"),
      ),
    )
    val runner = createRunner(
      launcher,
      RunnerFixtureConfig(
        catalogGateway = stubCatalogGateway(listOf(pack)),
        diffResolver = RecordingDiffResolver(default = diffFor("ui/Screen.kt")),
        budget = ReviewContextBudgetPolicy.DEFAULT.copy(maxRoutingAnalysisPairs = 1),
      ),
    )

    val error = assertFailsWith<ReviewContextBudgetExceededException> {
      runner.run(baseRequest(scope = ParallelReviewScope.STAGED))
    }
    assertEquals(REVIEW_ROUTING_ANALYSIS_PAIRS_BUDGET, error.outcome.budgetKind)
    assertTrue(launcher.requests.isEmpty(), "routing budget breach must not launch specialists")
  }
}
private data class RunnerFixtureConfig(
  val catalogGateway: ScaffoldCatalogGateway = stubCatalogGateway(),
  val diffResolver: DiffResolverPort = RealProcessDiffResolver(),
  val parallelLaneRunner: ParallelReviewLaneRunner = TestParallelLaneRunner(),
  val rubricResolver: ReviewRubricResolver = ReviewRubricResolver {
    ResolvedReviewRubric("parallel-code-review", "governed generic rubric")
  },
  val database: RecordingReviewDatabase = RecordingReviewDatabase(),
  val budget: ReviewContextBudgetPolicy = ReviewContextBudgetPolicy.DEFAULT,
) {
  val installedPackCatalog: InstalledPlatformPackCatalogPort =
    InstalledPlatformPackCatalogPort { catalogGateway.discoverPlatformManifests(Path.of(".")) }
}

private fun runner(
  launcher: GoalRunnerSubtaskLauncher,
  catalogGateway: ScaffoldCatalogGateway = stubCatalogGateway(),
  diffResolver: DiffResolverPort = RealProcessDiffResolver(),
  rubricResolver: ReviewRubricResolver = ReviewRubricResolver {
    ResolvedReviewRubric("parallel-code-review", "governed generic rubric")
  },
): ParallelCodeReviewRunner = createRunner(
  launcher,
  RunnerFixtureConfig(
    catalogGateway = catalogGateway,
    diffResolver = diffResolver,
    rubricResolver = rubricResolver,
  ),
)

private fun runnerWithParallelLane(
  launcher: GoalRunnerSubtaskLauncher,
  diffResolver: DiffResolverPort,
  parallelLaneRunner: ParallelReviewLaneRunner,
): ParallelCodeReviewRunner = createRunner(
  launcher,
  RunnerFixtureConfig(diffResolver = diffResolver, parallelLaneRunner = parallelLaneRunner),
)

private fun createRunner(launcher: GoalRunnerSubtaskLauncher, config: RunnerFixtureConfig): ParallelCodeReviewRunner =
  ParallelCodeReviewRunner(
    parentReviewLauncher = launcher,
    diffResolver = config.diffResolver,
    parallelLaneRunner = config.parallelLaneRunner,
    repoLocalConfig = object : RepoLocalConfigPort {
      override fun readRepoLocalConfig(request: skillbill.ports.config.model.ReadRepoLocalConfigRequest) =
        ReadRepoLocalConfigResult(RepoLocalConfig.defaults().copy(reviewContextBudget = config.budget))
    },
    reviewContextEnvelopeValidator = object : skillbill.review.context.ReviewContextEnvelopeValidator {
      override fun validate(envelope: Map<String, Any?>, sourceLabel: String) = Unit
    },
    reviewRubricResolver = config.rubricResolver,
    reviewSpecialistContractProvider = ReviewSpecialistContractProvider { TEST_SPECIALIST_CONTRACT },
    database = config.database,
    installedPackCatalog = config.installedPackCatalog,
  )

private class RecordingReviewDatabase : DatabaseSessionFactory {
  val laneWrites = mutableListOf<Pair<String, List<ReviewRunLane>>>()
  val findingLaneWrites = mutableListOf<Pair<String, Map<String, String>>>()

  private val reviews = Proxy.newProxyInstance(
    ReviewRepository::class.java.classLoader,
    arrayOf(ReviewRepository::class.java),
  ) { _, method, args ->
    when (method.name) {
      "saveAccounting" -> Unit
      "loadAccounting" -> null
      "replaceReviewRunLanes" -> {
        @Suppress("UNCHECKED_CAST")
        laneWrites += args[0] as String to (args[1] as List<ReviewRunLane>)
      }
      "fetchReviewRunLanes" -> laneWrites.lastOrNull()?.second.orEmpty()
      "fetchIntegrationPass" -> null
      "recordIntegrationPass" -> Unit
      "recordFindingLaneAttribution" -> {
        @Suppress("UNCHECKED_CAST")
        findingLaneWrites += args[0] as String to (args[1] as Map<String, String>)
      }
      "recordFindingVerdicts", "recordStageBoundary", "recordSpecProjectionReference" -> Unit
      "fetchFindingVerdicts" -> emptyList<skillbill.review.model.ReviewFindingVerdict>()
      "fetchStageBoundaries" -> emptyList<skillbill.review.model.ReviewStageBoundary>()
      "fetchSpecProjectionReference" -> null
      else -> error("Unexpected review repository call: ${method.name}")
    }
  } as ReviewRepository
  private val unitOfWork = Proxy.newProxyInstance(
    UnitOfWork::class.java.classLoader,
    arrayOf(UnitOfWork::class.java),
  ) { _, method, _ ->
    when (method.name) {
      "getReviews" -> reviews
      "getDbPath" -> Path.of("/tmp/noop-review.db")
      else -> error("Unexpected unit-of-work call: ${method.name}")
    }
  } as UnitOfWork

  override fun resolveDbPath(dbOverride: String?) = unitOfWork.dbPath
  override fun databaseExists(dbOverride: String?) = true
  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork)
  override fun <T> selfManagedWrite(dbOverride: String?, block: (UnitOfWork) -> T): T = transaction(dbOverride, block)

  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork)
}

private const val TEST_SPECIALIST_CONTRACT: String =
  "## Shared Contract For Every Specialist\n" +
    "- Evidence is mandatory\n" +
    "- Keep each specialist review pass to at most 7 findings\n\n" +
    "## Shared Report Structure\n" +
    "- [F-001] <Severity> | <Confidence> | <file:line> | <description>"

private val runnerRequestSequence = AtomicInteger()

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
  codeReviewMode = CodeReviewExecutionMode.INLINE,
  reviewRunId = "runner-test-${runnerRequestSequence.incrementAndGet()}",
  // Pinned so most fixtures never reach for Git; a scope test that exercises base or head detection
  // clears them with `detectingRevisions()` to leave the resolution the runner performs visible.
  baseRevision = "base-revision",
  headRevision = "head-revision",
)

/** Drops the pinned revisions so the runner resolves the scope's own base and head. */
private fun ParallelCodeReviewRequest.detectingRevisions() = copy(baseRevision = null, headRevision = null)

private fun alwaysSuccessLauncher(stdout: String = "NO_FINDINGS") = GoalRunnerSubtaskLauncher { request ->
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
  assertFailsWith<UsageValidationException> { block() }
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

private class ParallelSubtaskLauncher(
  private val outcome: AgentRunLaunchOutcome? = null,
) : GoalRunnerSubtaskLauncher {
  val requests: MutableList<GoalRunnerSubtaskLaunchRequest> = CopyOnWriteArrayList()

  override fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome {
    requests += request
    return outcome ?: AgentRunLaunchFacts(
      agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
      exitStatus = 0,
      stdout = "NO_FINDINGS",
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
  contractVersion = "1.3",
  routingSignals = RoutingSignals(strong = strongSignals, tieBreakers = emptyList()),
  declaredCodeReviewAreas = listOf("architecture", "testing"),
  declaredFiles = DeclaredFiles(
    baseline = Path.of("content.md"),
    areas = mapOf("architecture" to Path.of("architecture.md"), "testing" to Path.of("testing.md")),
  ),
  areaMetadata = emptyMap(),
  laneConditions = mapOf(
    "architecture" to ReviewLaneCondition(required = true),
    "testing" to ReviewLaneCondition(path = listOf("Test.kt")),
  ),
)

private fun sparsePlatformManifest(
  requiredArea: String,
  pathAreas: Map<String, List<String>>,
  slug: String = "kotlin",
  strongSignals: List<String> = listOf("*.kt"),
): PlatformManifest {
  val areas = listOf(requiredArea) + pathAreas.keys.toList()
  return PlatformManifest(
    slug = slug,
    packRoot = Path.of("platform-packs/$slug"),
    contractVersion = "1.3",
    routingSignals = RoutingSignals(strong = strongSignals, tieBreakers = emptyList()),
    declaredCodeReviewAreas = areas,
    declaredFiles = DeclaredFiles(
      baseline = Path.of("content.md"),
      areas = areas.associateWith { Path.of("$it.md") },
    ),
    areaMetadata = emptyMap(),
    laneConditions = buildMap {
      put(requiredArea, ReviewLaneCondition(required = true))
      pathAreas.forEach { (area, paths) -> put(area, ReviewLaneCondition(path = paths)) }
    },
  )
}

private fun fallbackManifest(): PlatformManifest = platformManifest("generic", listOf("fallback-only")).copy(
  routingSignals = RoutingSignals(
    strong = listOf("fallback-only"),
    tieBreakers = emptyList(),
    path = emptyList(),
    content = emptyList(),
  ),
  fallbackCapabilities = setOf("code-review"),
)

private fun diffFor(path: String): String = "+++ b/$path"

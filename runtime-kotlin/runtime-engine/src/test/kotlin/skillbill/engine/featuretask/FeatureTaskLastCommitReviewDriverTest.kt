package skillbill.engine.featuretask

import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.review.context.model.CodeReviewExecutionMode
import skillbill.review.model.ParallelReviewSeverity
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskLastCommitReviewDriverTest {
  @Test
  fun `last-commit review launches one mutating agent in the repo without evidence isolation`() {
    val captured = mutableListOf<GoalRunnerSubtaskLaunchRequest>()
    val driver = FeatureTaskLastCommitReviewDriver(
      GoalRunnerSubtaskLauncher { launch ->
        captured += launch
        AgentRunLaunchFacts(
          agent = InstallAgent.CURSOR,
          exitStatus = 0,
          stdout = "reviewed last commit\nverdict: approved",
          stderr = "",
          timedOut = false,
          spawnFailed = false,
        )
      },
    )

    val result = driver.run(reviewRequest())

    val launch = captured.single()
    val skill = launch.skillRunRequest
    assertEquals("cursor", launch.invokedAgentId)
    assertNull(launch.configuredAgentOverrideId)
    assertEquals(Path.of("/tmp/repo"), skill.repoRoot)
    assertEquals("wftr-review-child", skill.issueKey)
    assertNull(skill.reviewEvidenceBroker)
    assertNull(skill.nativeReviewOperations)
    assertNull(skill.reviewEvidenceEndpoint)
    assertFalse(skill.readOnlyPhase)
    assertNull(skill.progressIdleTimeout)
    val prompt = requireNotNull(skill.promptOverride)
    assertTrue(prompt.contains("git diff abc^ def"))
    assertTrue(prompt.contains("Fix every Blocker and Major"))
    assertTrue(prompt.contains("You may edit files"))
    assertTrue(prompt.contains("the runtime owns the review checkpoint"))
    assertTrue(result.lane1.success)
    assertEquals("reviewed last commit\nverdict: approved", result.output)
    assertEquals(emptyList(), result.mergeResult.findings)
  }

  @Test
  fun `remaining blocker findings parse into the driver register`() {
    val driver = FeatureTaskLastCommitReviewDriver(
      GoalRunnerSubtaskLauncher {
        AgentRunLaunchFacts(
          agent = InstallAgent.CURSOR,
          exitStatus = 0,
          stdout = "- [F-001] Blocker | High | src/main/App.kt:42 | remaining defect\nverdict: changes_requested",
          stderr = "",
          timedOut = false,
          spawnFailed = false,
        )
      },
    )

    val result = driver.run(reviewRequest())

    val finding = result.mergeResult.findings.single()
    assertEquals(ParallelReviewSeverity.BLOCKER, finding.severity)
    assertEquals("src/main/App.kt", finding.repositoryPath)
    assertEquals(42, finding.line)
    assertEquals("remaining defect", finding.description)
    assertTrue(result.lane1.success)
  }

  @Test
  fun `a hung child is a failed lane not an approved empty register`() {
    val driver = FeatureTaskLastCommitReviewDriver(
      GoalRunnerSubtaskLauncher {
        AgentRunLaunchFacts(
          agent = InstallAgent.CURSOR,
          exitStatus = null,
          stdout = "",
          stderr = "",
          timedOut = true,
          spawnFailed = false,
        )
      },
    )

    val result = driver.run(reviewRequest())

    assertFalse(result.lane1.success)
    assertEquals("agent timed out", result.lane1.failureReason)
    assertEquals(emptyList(), result.mergeResult.findings)
  }

  @Test
  fun `unsupported agent fails the lane`() {
    val driver = FeatureTaskLastCommitReviewDriver(
      GoalRunnerSubtaskLauncher {
        UnsupportedAgentRunLaunch(agent = InstallAgent.CURSOR, reason = "cursor is not installed")
      },
    )

    val result = driver.run(reviewRequest())

    assertFalse(result.lane1.success)
    assertEquals("cursor is not installed", result.lane1.failureReason)
  }

  private fun reviewRequest() = ParallelCodeReviewRequest(
    agent1Id = "cursor",
    scope = ParallelReviewScope.BRANCH,
    repoRoot = Path.of("/tmp/repo"),
    timeout = null,
    codeReviewMode = CodeReviewExecutionMode.INLINE,
    reviewRunId = "rvw-last-commit",
    activityWorkflowId = "wftr-review-child",
    baseRevision = "abc^",
    headRevision = "def",
  )
}

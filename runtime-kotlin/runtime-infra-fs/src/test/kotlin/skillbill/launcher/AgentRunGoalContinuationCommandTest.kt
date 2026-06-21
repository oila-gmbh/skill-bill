package skillbill.launcher

import skillbill.install.model.InstallAgent
import skillbill.launcher.agentrun.headlessAgentRunAdapters
import skillbill.ports.agentrun.model.SkillRunGoalContinuationContext
import skillbill.ports.agentrun.model.SkillRunRequest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AgentRunGoalContinuationCommandTest {
  @Test
  fun `goal-continuation child with no child workflow id runs skill-bill feature-task run directly`() {
    val runner = RecordingAgentRunProcessRunner()
    val outcome = requireNotNull(headlessAgentRunAdapters(runner)[InstallAgent.CLAUDE]).launch(skillRunRequest())

    assertEquals(InstallAgent.CLAUDE, outcome.agent)
    val request = runner.requests.single()
    assertEquals(
      listOf(
        "skill-bill",
        "--db",
        "/tmp/skillbill-agent-run/metrics.db",
        "feature-task",
        "run",
        "SKILL-56",
        ".feature-specs/SKILL-56-goal/spec_subtask_2.md",
        "--goal-parent-issue-key",
        "SKILL-56",
        "--goal-subtask-id",
        "2",
        "--goal-branch",
        "feat/SKILL-56-goal",
        "--suppress-pr",
        "--goal-parent-workflow-id",
        "wfl-parent",
        "--goal-last-resumable-step",
        "implement",
        "--agent",
        "claude",
      ),
      request.command,
    )
    assertNull(request.stdinText)
    assertFalse(request.command.any { value -> "bill-feature-task" in value })
    assertFalse(request.command.any { value -> value == "claude" && request.command.indexOf(value) == 0 })
    assertEquals("1", request.environment["SKILL_BILL_GOAL_CONTINUATION"])
    assertTrue(request.inheritEnvironment)
  }

  @Test
  fun `goal-continuation child with existing child workflow id runs feature-task resume`() {
    val runner = RecordingAgentRunProcessRunner()
    requireNotNull(headlessAgentRunAdapters(runner)[InstallAgent.CLAUDE]).launch(
      skillRunRequest(goalContinuation = goalContinuationContext(childWorkflowId = "wfl-child-runtime")),
    )

    val request = runner.requests.single()
    assertEquals(
      listOf(
        "skill-bill",
        "--db",
        "/tmp/skillbill-agent-run/metrics.db",
        "feature-task",
        "resume",
        "wfl-child-runtime",
        "SKILL-56",
        ".feature-specs/SKILL-56-goal/spec_subtask_2.md",
        "--goal-parent-issue-key",
        "SKILL-56",
        "--goal-subtask-id",
        "2",
        "--goal-branch",
        "feat/SKILL-56-goal",
        "--suppress-pr",
        "--goal-parent-workflow-id",
        "wfl-parent",
        "--goal-last-resumable-step",
        "implement",
        "--agent",
        "claude",
      ),
      request.command,
    )
    assertNull(request.stdinText)
    assertFalse(request.command.any { value -> "bill-feature-task" in value })
    assertFalse(request.command.contains("--workflow-id"))
  }

  @Test
  fun `goal-continuation child with assigned workflow id and no child runs feature-task run with workflow-id`() {
    val runner = RecordingAgentRunProcessRunner()
    requireNotNull(headlessAgentRunAdapters(runner)[InstallAgent.CLAUDE]).launch(
      skillRunRequest(
        goalContinuation = goalContinuationContext(childWorkflowId = null, assignedWorkflowId = "wfl-assigned"),
      ),
    )

    val request = runner.requests.single()
    assertContains(request.command, "feature-task")
    assertContains(request.command, "run")
    assertContains(request.command, "SKILL-56")
    assertContains(request.command, ".feature-specs/SKILL-56-goal/spec_subtask_2.md")
    val workflowIdFlagIndex = request.command.indexOf("--workflow-id")
    assertTrue(workflowIdFlagIndex >= 0)
    assertEquals("wfl-assigned", request.command[workflowIdFlagIndex + 1])
    assertFalse(request.command.contains("resume"))
  }

  @Test
  fun `every agent goal-continuation child spawns skill-bill feature-task and never the skill`() {
    val runner = RecordingAgentRunProcessRunner()
    listOf(InstallAgent.CLAUDE, InstallAgent.CODEX, InstallAgent.OPENCODE, InstallAgent.JUNIE).forEach { agent ->
      requireNotNull(headlessAgentRunAdapters(runner)[agent]).launch(skillRunRequest())
    }

    assertEquals(4, runner.requests.size)
    runner.requests.forEach { request ->
      assertEquals(
        listOf("skill-bill", "--db", "/tmp/skillbill-agent-run/metrics.db", "feature-task"),
        request.command.take(4),
      )
      assertContains(request.command, "run")
      assertContains(request.command, "--suppress-pr")
      assertNull(request.stdinText)
      assertFalse(request.command.any { value -> "bill-feature-task" in value })
      assertFalse(request.command.any { value -> "workflow continue" in value })
      assertFalse(request.command.any { value -> "use the" in value.lowercase() && "skill" in value.lowercase() })
    }
    val perAgent = listOf("claude", "codex", "opencode", "junie")
    runner.requests.forEachIndexed { index, request ->
      assertEquals(perAgent[index], request.command.last())
      assertEquals("--agent", request.command[request.command.size - 2])
    }
  }

  @Test
  fun `goal-continuation child always carries suppress-pr`() {
    val runner = RecordingAgentRunProcessRunner()
    requireNotNull(headlessAgentRunAdapters(runner)[InstallAgent.CODEX]).launch(skillRunRequest())

    assertContains(runner.requests.single().command, "--suppress-pr")
  }

  private fun skillRunRequest(
    goalContinuation: SkillRunGoalContinuationContext? = goalContinuationContext(),
  ): SkillRunRequest = SkillRunRequest(
    issueKey = "SKILL-56",
    repoRoot = Path.of("/tmp/skillbill-agent-run"),
    subtaskId = 2,
    dbPathOverride = "/tmp/skillbill-agent-run/metrics.db",
    timeout = 3.seconds,
    goalContinuation = goalContinuation,
  )

  private fun goalContinuationContext(
    childWorkflowId: String? = null,
    assignedWorkflowId: String? = null,
  ): SkillRunGoalContinuationContext = SkillRunGoalContinuationContext(
    parentIssueKey = "SKILL-56",
    subtaskId = 2,
    goalBranch = "feat/SKILL-56-goal",
    suppressPr = true,
    specPath = ".feature-specs/SKILL-56-goal/spec_subtask_2.md",
    parentWorkflowId = "wfl-parent",
    lastResumableStep = "implement",
    childWorkflowId = childWorkflowId,
    assignedWorkflowId = assignedWorkflowId,
  )
}

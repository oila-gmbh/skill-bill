package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliBillMonitorSkillRuntimeTest {
  @Test
  fun `monitor takes one bounded status snapshot without launching a goal`() {
    val fixture = goalFixture(subtaskCount = 2)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val result = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "status",
        "SKILL-901",
        "--repo-root",
        fixture.tempDir.toString(),
        "--monitor",
      ),
      fixture.context(launcher = launcher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(
      setOf(
        "status",
        "issue_key",
        "complete_count",
        "pending_count",
        "blocked_count",
        "current_subtask",
        "current_step",
        "execution_liveness",
        "resumable_state",
      ),
      result.payload!!.keys,
    )
    assertContains(result.stdout, "complete: 0")
    assertContains(result.stdout, "pending: 2")
    assertContains(result.stdout, "blocked: 0")
    assertContains(result.stdout, "resumable_state:")
    assertFalse(result.stdout.contains("active_agent"))
    assertFalse(result.stdout.contains("paused"))
    assertFalse(result.stdout.contains("planning"))
    assertFalse(result.stdout.contains("skill-bill goal watch"))
    assertTrue(launcher.childLaunches.isEmpty())
  }

  @Test
  fun `monitor rejects malformed issue keys before the read-only status seam`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val result = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "status",
        "skill-901",
        "--repo-root",
        fixture.tempDir.toString(),
        "--monitor",
      ),
      fixture.context(launcher = launcher),
    )

    assertEquals(1, result.exitCode)
    assertContains(result.stdout, "Monitor requires one supported issue key")
    assertTrue(launcher.childLaunches.isEmpty())
  }

  @Test
  fun `monitor reports a missing issue without falling through to implementation`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val result = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "status",
        "SKILL-999",
        "--repo-root",
        fixture.tempDir.toString(),
        "--monitor",
      ),
      fixture.context(launcher = launcher),
    )

    assertEquals(1, result.exitCode)
    assertContains(result.stdout, "status: not_found")
    assertContains(result.stdout, "resumable_state: not_found")
    assertTrue(launcher.childLaunches.isEmpty())
  }

  @Test
  fun `monitor status help documents one bounded snapshot`() {
    val result = CliRuntime.run(listOf("goal", "status", "--help"), CliRuntimeContext())

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "--monitor")
    assertContains(result.stdout, "one bounded read-only snapshot")
  }
}

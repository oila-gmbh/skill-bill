package skillbill.cli

import skillbill.cli.core.CliRuntime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

// The soft reset never consults --preserve-planning, so accepting the pair silently discarded the
// operator's intent. The gate mirrors how --confirm-issue-key is bound to --hard.
class CliGoalResetOptionGateTest {
  @Test
  fun `goal reset rejects preserve-planning without hard`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val rejected = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "goal", "reset", "SKILL-901", "--preserve-planning"),
      fixture.context(launcher = launcher),
    )

    assertEquals(1, rejected.exitCode, rejected.stdout)
    assertContains(rejected.stdout, "--preserve-planning only applies to a hard reset")
  }

  @Test
  fun `goal reset accepts preserve-planning with a confirmed hard reset`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val accepted = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "reset",
        "SKILL-901",
        "--hard",
        "--preserve-planning",
        "--confirm-issue-key",
        "SKILL-901",
      ),
      fixture.context(launcher = launcher),
    )

    assertEquals(0, accepted.exitCode, accepted.stdout)
    assertContains(accepted.stdout, "mode: hard")
  }
}

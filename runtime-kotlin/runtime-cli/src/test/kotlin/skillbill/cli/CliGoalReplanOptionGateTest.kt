package skillbill.cli

import skillbill.cli.core.CliRuntime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CliGoalReplanOptionGateTest {
  @Test
  fun `goal replan requires --subtask`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val rejected = CliRuntime.run(
      listOf("--db", fixture.dbPath.toString(), "goal", "replan", "SKILL-901"),
      fixture.context(launcher = launcher),
    )

    assertEquals(1, rejected.exitCode, rejected.stdout)
    assertContains(rejected.stdout, "--subtask")
  }

  @Test
  fun `goal replan rejects non-positive --subtask`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val rejected = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "replan",
        "SKILL-901",
        "--subtask",
        "0",
      ),
      fixture.context(launcher = launcher),
    )

    assertEquals(1, rejected.exitCode, rejected.stdout)
    assertContains(rejected.stdout, "--subtask must be a positive integer")
  }
}

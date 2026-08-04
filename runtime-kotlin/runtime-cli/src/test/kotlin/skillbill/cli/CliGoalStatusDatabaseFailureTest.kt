package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.error.DatabaseAccessError
import skillbill.error.SkillBillRuntimeException
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CliGoalStatusDatabaseFailureTest {
  @Test
  fun `monitor renders an unreadable database as a bounded failure payload`() {
    val fixture = goalFixture(subtaskCount = 2)

    val result = monitorStatus(fixture, dbPath = unopenableDatabasePath(fixture.tempDir))

    assertEquals(1, result.exitCode, result.stdout)
    assertEquals("database_unavailable", result.payload?.get("status"), result.stdout)
    assertEquals("database_unavailable", result.payload?.get("resumable_state"), result.stdout)
    assertContains(result.stdout, "status: database_unavailable")
    assertContains(result.stdout, "reason: ")
  }

  @Test
  fun `the failure payload is distinguishable from not_found and from a healthy snapshot`() {
    val fixture = goalFixture(subtaskCount = 2)

    val healthy = monitorStatus(fixture, dbPath = fixture.dbPath)
    val notFound = monitorStatus(fixture, dbPath = fixture.dbPath, issueKey = "SKILL-902")
    val failure = monitorStatus(fixture, dbPath = unopenableDatabasePath(fixture.tempDir))

    assertEquals(0, healthy.exitCode, healthy.stdout)
    assertEquals("not_found", notFound.payload?.get("status"))
    assertNotEquals(notFound.payload, failure.payload)
    assertNotEquals(healthy.payload, failure.payload)
    assertFalse(healthy.payload!!.containsKey("status"), healthy.stdout)
    assertEquals(1, failure.exitCode, failure.stdout)
  }

  @Test
  fun `the failure surface carries no jdbc detail, stack frames, or unbounded text`() {
    val fixture = goalFixture(subtaskCount = 1)

    val failure = monitorStatus(fixture, dbPath = unopenableDatabasePath(fixture.tempDir))

    assertFalse(failure.stdout.contains("org.sqlite"), failure.stdout)
    assertFalse(failure.stdout.contains("SQLiteException"), failure.stdout)
    assertFalse(failure.stdout.lines().any { it.trimStart().startsWith("at ") }, failure.stdout)
    assertFalse(failure.stdout.contains("child-"), failure.stdout)
    val reason = failure.payload?.get("reason") as String
    assertTrue(reason.length <= MAX_BOUNDED_REASON_CHARS, "reason length was ${reason.length}")
    assertEquals(1, reason.lines().size, reason)
    assertEquals(
      setOf("status", "issue_key", "resumable_state", "reason"),
      failure.payload!!.keys,
      failure.stdout,
    )
  }

  @Test
  fun `the non-monitor status path returns a bounded non-zero result without jdbc detail`() {
    val fixture = goalFixture(subtaskCount = 1)
    val unopenable = unopenableDatabasePath(fixture.tempDir)

    val result = CliRuntime.run(
      listOf("--db", unopenable.toString(), "goal", "status", "SKILL-901"),
      fixture.context(launcher = UnusedStatusAgentRunLauncher),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertFalse(result.stdout.contains("org.sqlite"), result.stdout)
    assertFalse(result.stdout.lines().any { it.trimStart().startsWith("at ") }, result.stdout)
    assertContains(result.stdout, unopenable.toAbsolutePath().normalize().toString())
  }

  @Test
  fun `an unrelated typed failure still propagates past the cli boundary`() {
    val fixture = goalFixture(subtaskCount = 1)
    val missingSource = fixture.tempDir.resolve("absent-skill")

    val error = assertFailsWith<SkillBillRuntimeException> {
      CliRuntime.run(
        listOf(
          "install",
          "link-skill",
          "--source",
          missingSource.toString(),
          "--target-dir",
          fixture.tempDir.resolve("agent/skills").toString(),
          "--agent",
          "codex",
        ),
        fixture.context(launcher = UnusedStatusAgentRunLauncher),
      )
    }

    assertFalse(error is DatabaseAccessError, "the new database catch swallowed an unrelated failure: $error")
  }

  private fun monitorStatus(
    fixture: GoalCliFixture,
    dbPath: Path,
    issueKey: String = "SKILL-901",
  ) = CliRuntime.run(
    listOf(
      "--db",
      dbPath.toString(),
      "goal",
      "status",
      issueKey,
      "--repo-root",
      fixture.tempDir.toString(),
      "--monitor",
    ),
    fixture.context(launcher = UnusedStatusAgentRunLauncher),
  )

  private fun unopenableDatabasePath(tempDir: Path): Path =
    tempDir.resolve("unopenable-metrics.db").also { Files.createDirectories(it) }
}

private const val MAX_BOUNDED_REASON_CHARS = 240

private object UnusedStatusAgentRunLauncher : AgentRunLauncher {
  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome = error("Unexpected launch")
}

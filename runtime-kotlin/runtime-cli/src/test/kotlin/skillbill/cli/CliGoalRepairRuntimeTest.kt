package skillbill.cli

import skillbill.application.decomposition.decodeArtifacts
import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonCodec
import skillbill.infrastructure.sqlite.core.DatabaseRuntime
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phaseartifacts.phaseRecordsFrom
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SKILL-176 subtask 5 CLI surface for `goal repair`. Kept outside [CliGoalRuntimeTest] so that
 * suite stays under the detekt LargeClass threshold.
 */
class CliGoalRepairRuntimeTest {
  @Test
  fun `goal repair help enumerates wedge classes and does-not-touch statement`() {
    val result = CliRuntime.run(
      listOf("goal", "repair", "--help"),
      CliRuntimeContext(environment = emptyMap(), executableLookup = ExecutableLookup { true }),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "validation_depth")
    assertContains(result.stdout, "review_base_sha")
    assertContains(result.stdout, "remediation_base_sha")
    assertContains(result.stdout, "goal_continuation_outcome")
    assertContains(result.stdout, "completed upstream")
    // Clikt wraps help across lines; normalize whitespace so mid-phrase wraps do not flake.
    val help = result.stdout.replace(Regex("\\s+"), " ")
    assertContains(help, "Does not touch")
    assertContains(help, "completed commit shas")
    assertContains(help, "review pass history")
    assertContains(help, "audit repair state")
    assertContains(result.stdout, "--apply")
  }

  @Test
  fun `goal repair inspect-only on a healthy fixture exits zero and writes nothing`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))
    val before = readAllRuntimeArtifactsJson(fixture)

    val result = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "repair",
        "SKILL-901",
        "--repo-root",
        fixture.tempDir.toString(),
      ),
      fixture.context(launcher = NoopGoalTestAgentRunLauncher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: healthy")
    assertEquals(before, readAllRuntimeArtifactsJson(fixture))
  }

  @Test
  fun `goal repair unknown issue exits non-zero as not_found`() {
    val fixture = goalFixture(subtaskCount = 1)
    val result = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "repair",
        "SKILL-404",
        "--repo-root",
        fixture.tempDir.toString(),
      ),
      fixture.context(launcher = NoopGoalTestAgentRunLauncher),
    )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: not_found")
  }

  @Test
  fun `goal repair inspect reports missing validation_depth without applying`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))
    val childWorkflowId = resolveCompletedChildWorkflowId(fixture)
    stripValidationDepth(fixture, childWorkflowId)
    val before = readAllRuntimeArtifactsJson(fixture)

    val result = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "repair",
        "SKILL-901",
        "--repo-root",
        fixture.tempDir.toString(),
      ),
      fixture.context(launcher = NoopGoalTestAgentRunLauncher),
    )

    assertEquals(2, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: inspected")
    assertContains(result.stdout, "missing_validation_depth")
    assertContains(result.stdout, "validation_depth")
    assertFalse(result.stdout.contains("applied_repairs:"))
    assertEquals(before, readAllRuntimeArtifactsJson(fixture))
  }

  @Test
  fun `goal repair apply clears missing validation_depth and records evidence`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))
    val childWorkflowId = resolveCompletedChildWorkflowId(fixture)
    stripValidationDepth(fixture, childWorkflowId)

    val result = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "repair",
        "SKILL-901",
        "--repo-root",
        fixture.tempDir.toString(),
        "--apply",
      ),
      fixture.context(launcher = NoopGoalTestAgentRunLauncher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: repaired")
    assertContains(result.stdout, "applied_repairs:")
    assertContains(result.stdout, "validation_depth")
    val artifacts = readChildArtifacts(fixture, childWorkflowId)
    assertTrue(artifacts.contains("\"validation_depth\""))
    assertTrue(artifacts.contains("goal_child_repair_evidence"))
  }

  @Test
  fun `goal repair apply reopens completed upstream output wedge through sqlite`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))
    val childWorkflowId = resolveCompletedChildWorkflowId(fixture)
    seedCompletedUpstreamMissingOutput(fixture, childWorkflowId)

    val result = CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "repair",
        "SKILL-901",
        "--repo-root",
        fixture.tempDir.toString(),
        "--apply",
      ),
      fixture.context(launcher = NoopGoalTestAgentRunLauncher),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: repaired")
    assertContains(result.stdout, "completed_upstream_missing_output")
    val repairedRecords = phaseRecordsFrom(decodeArtifacts(readChildArtifacts(fixture, childWorkflowId)))
    assertEquals(WorkflowStepStatus.PENDING, repairedRecords.getValue("verify_findings").status)
    assertEquals(WorkflowStepStatus.PENDING, repairedRecords.getValue("implement_fix").status)
  }

  private fun resolveCompletedChildWorkflowId(fixture: GoalCliFixture): String =
    DatabaseRuntime.ensureDatabase(fixture.dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery(
          "SELECT workflow_id FROM feature_task_workflows " +
            "WHERE mode = 'runtime' AND instr(artifacts_json, 'goal_continuation') > 0 " +
            "ORDER BY workflow_id LIMIT 1",
        ).use { rows ->
          check(rows.next()) { "no goal-continuation child found" }
          rows.getString(1)
        }
      }
    }

  private fun readAllRuntimeArtifactsJson(fixture: GoalCliFixture): List<String> =
    DatabaseRuntime.ensureDatabase(fixture.dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery(
          "SELECT workflow_id, artifacts_json FROM feature_task_workflows WHERE mode = 'runtime' ORDER BY workflow_id",
        ).use { rows ->
          buildList {
            while (rows.next()) {
              add(rows.getString(1) + "=" + rows.getString(2))
            }
          }
        }
      }
    }

  private fun readChildArtifacts(fixture: GoalCliFixture, workflowId: String): String =
    DatabaseRuntime.ensureDatabase(fixture.dbPath).use { connection ->
      connection.prepareStatement(
        "SELECT artifacts_json FROM feature_task_workflows WHERE workflow_id = ?",
      ).use { statement ->
        statement.setString(1, workflowId)
        statement.executeQuery().use { rows ->
          check(rows.next()) { "missing child $workflowId" }
          rows.getString(1)
        }
      }
    }

  private fun stripValidationDepth(fixture: GoalCliFixture, workflowId: String) {
    DatabaseRuntime.ensureDatabase(fixture.dbPath).use { connection ->
      val current = connection.prepareStatement(
        "SELECT artifacts_json FROM feature_task_workflows WHERE workflow_id = ?",
      ).use { statement ->
        statement.setString(1, workflowId)
        statement.executeQuery().use { rows ->
          check(rows.next()) { "missing child $workflowId" }
          rows.getString(1)
        }
      }
      val stripped = current
        .replace(Regex(""""validation_depth"\s*:\s*"full"\s*,?"""), "")
        .replace(",}", "}")
        .replace(",", ",")
      check(stripped != current) { "could not strip validation_depth from $workflowId" }
      connection.prepareStatement(
        "UPDATE feature_task_workflows SET artifacts_json = ? WHERE workflow_id = ?",
      ).use { statement ->
        statement.setString(1, stripped)
        statement.setString(2, workflowId)
        check(statement.executeUpdate() == 1)
      }
    }
  }

  private fun seedCompletedUpstreamMissingOutput(fixture: GoalCliFixture, workflowId: String) {
    DatabaseRuntime.ensureDatabase(fixture.dbPath).use { connection ->
      val current = connection.prepareStatement(
        "SELECT artifacts_json FROM feature_task_workflows WHERE workflow_id = ?",
      ).use { statement ->
        statement.setString(1, workflowId)
        statement.executeQuery().use { rows ->
          check(rows.next()) { "missing child $workflowId" }
          rows.getString(1)
        }
      }
      val artifacts = decodeArtifacts(current).toMutableMap()
      val records = phaseRecordsFrom(artifacts).toMutableMap()
      val timestamp = "2026-09-12T08:00:00Z"
      records.putIfAbsent(
        "verify_findings",
        FeatureTaskRuntimePhaseRecord(
          phaseId = "verify_findings",
          status = WorkflowStepStatus.COMPLETED,
          attemptCount = 1,
          startedAt = timestamp,
          resolvedAgentId = "codex",
        ),
      )
      records.putIfAbsent(
        "implement_fix",
        FeatureTaskRuntimePhaseRecord(
          phaseId = "implement_fix",
          status = WorkflowStepStatus.BLOCKED,
          attemptCount = 1,
          startedAt = timestamp,
          resolvedAgentId = "codex",
        ),
      )
      records["verify_findings"] = records.getValue("verify_findings").copy(
        status = WorkflowStepStatus.COMPLETED,
        finishedAt = null,
        durationMillis = null,
        outputArtifact = null,
      )
      records["implement_fix"] = records.getValue("implement_fix").copy(
        status = WorkflowStepStatus.BLOCKED,
        finishedAt = null,
        durationMillis = null,
        outputArtifact = null,
        blockedReason =
        "Phase 'implement_fix' requires upstream output(s) verify_findings that are not present",
      )
      artifacts[FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY] =
        records.mapValues { (_, record) -> record.toArtifactMap() }
      connection.prepareStatement(
        "UPDATE feature_task_workflows SET artifacts_json = ? WHERE workflow_id = ?",
      ).use { statement ->
        statement.setString(1, JsonCodec.mapToJsonString(artifacts))
        statement.setString(2, workflowId)
        check(statement.executeUpdate() == 1)
      }
    }
  }
}

package skillbill.db

import skillbill.db.core.DatabaseRuntime
import skillbill.db.workflow.GoalRunnerControlStore
import skillbill.db.workflow.LEGACY_UNKNOWN_PAUSED_AT
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.goalrunner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.model.GoalRunnerReviewPolicy
import skillbill.workflow.model.CodeReviewExecutionMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoalRunnerControlStoreTest {
  @Test
  fun `review policy and operator acceptance remain durable outside workflow projection`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-controls").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalRunnerControlStore(connection)
      val policy = GoalRunnerReviewPolicy(CodeReviewExecutionMode.INLINE, parallelReviewAgent = "reviewer")
      val acceptance = GoalRunnerOutOfBandAcceptance(
        subtaskId = 2,
        commitSha = "abc123",
        reason = "work was completed on the feature branch",
        acceptedAt = "2026-08-01T10:00:00Z",
      )

      store.persistReviewPolicy("parent-1", policy)
      store.persistOutOfBandAcceptance("parent-1", acceptance)

      assertEquals(policy, store.reviewPolicy("parent-1"))
      assertEquals(mapOf(2 to acceptance), store.outOfBandAcceptances("parent-1"))
      assertEquals(GoalRunnerControlState(), store.controlState("parent-1"))
      connection.prepareStatement(
        "SELECT review_policy_json, out_of_band_acceptances_json " +
          "FROM goal_runner_controls WHERE parent_workflow_id = ?",
      ).use { statement ->
        statement.setString(1, "parent-1")
        statement.executeQuery().use { rows ->
          check(rows.next())
          check(rows.getString("review_policy_json").contains("code_review_mode"))
          check(rows.getString("out_of_band_acceptances_json").contains("commit_sha"))
        }
      }
    }
  }

  @Test
  fun `missing control state is legacy compatible and malformed state fails loudly`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-control-state").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalRunnerControlStore(connection)
      assertEquals(GoalRunnerControlState(), store.controlState("missing-parent"))

      val state = GoalRunnerControlState(
        stopAfterSubtaskId = 2,
        pauseRequested = true,
        pauseConsumed = true,
        paused = true,
        pauseReason = "operator_request",
        pausedAt = "2026-08-02T09:00:00Z",
      )
      store.persistControlState("parent-1", state)
      assertEquals(state, store.controlState("parent-1"))

      connection.prepareStatement(
        "UPDATE goal_runner_controls SET control_state_json = ? WHERE parent_workflow_id = ?",
      ).use { statement ->
        statement.setString(1, "{\"paused\":true,\"unsupported\":true}")
        statement.setString(2, "parent-1")
        statement.executeUpdate()
      }
      assertFailsWith<IllegalArgumentException> { store.controlState("parent-1") }
    }
  }

  @Test
  fun `control state survives a reopened database and duplicate writes remain stable`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-control-restart").resolve("metrics.db")
    val state = GoalRunnerControlState(
      stopAfterSubtaskId = 4,
      pauseRequested = true,
      pauseConsumed = true,
      paused = true,
      pauseReason = "operator_request",
      pausedAt = "2026-08-02T09:00:00Z",
    )

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalRunnerControlStore(connection)
      assertEquals(state, store.persistControlState("parent-restart", state))
      assertEquals(state, store.persistControlState("parent-restart", state))
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(state, GoalRunnerControlStore(connection).controlState("parent-restart"))
    }
  }

  @Test
  fun `a paused record written before paused_at existed decodes from the lease heartbeat`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-legacy-paused").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalRunnerControlStore(connection)
      store.persistControlState("parent-legacy", GoalRunnerControlState())
      writeRawControlState(
        connection,
        "parent-legacy",
        """
        {"paused":true,"pause_requested":true,"pause_consumed":true,"pause_reason":"operator_request",
         "execution_lease":{"generation":1,"owner_token":"owner-token-123456","host_identity":"host",
         "boot_identity":"boot","pid":42,"process_birth_token":"birth",
         "heartbeat_at":"2026-08-02T10:00:10Z","expires_at":"2026-08-02T10:00:40Z"}}
        """.trimIndent(),
      )

      val decoded = store.controlState("parent-legacy")
      assertTrue(decoded.paused)
      assertEquals("2026-08-02T10:00:10Z", decoded.pausedAt)
    }
  }

  @Test
  fun `a paused legacy record with no lease decodes to the unknown-time sentinel rather than failing`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-legacy-sentinel").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalRunnerControlStore(connection)
      store.persistControlState("parent-legacy", GoalRunnerControlState())
      writeRawControlState(
        connection,
        "parent-legacy",
        """{"paused":true,"pause_requested":true,"pause_consumed":true,"pause_reason":"operator_request"}""",
      )

      assertEquals(LEGACY_UNKNOWN_PAUSED_AT, store.controlState("parent-legacy").pausedAt)
    }
  }

  private fun writeRawControlState(connection: java.sql.Connection, parentWorkflowId: String, json: String) {
    connection.prepareStatement(
      "UPDATE goal_runner_controls SET control_state_json = ? WHERE parent_workflow_id = ?",
    ).use { statement ->
      statement.setString(1, json)
      statement.setString(2, parentWorkflowId)
      statement.executeUpdate()
    }
  }

  @Test
  fun `parent execution lease survives a reopened database`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-execution-lease").resolve("metrics.db")
    val lease = GoalRunnerExecutionLease(
      generation = 1,
      ownerToken = "owner-token-123456",
      hostIdentity = "host",
      bootIdentity = "boot",
      pid = 42,
      processBirthToken = "birth",
      heartbeatAt = "2026-08-02T10:00:00Z",
      expiresAt = "2026-08-02T10:00:30Z",
    )

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalRunnerControlStore(connection)
      assertTrue(store.acquireExecutionLease("parent-lease", lease))
      assertEquals(lease, store.executionLease("parent-lease"))
      assertTrue(
        store.heartbeatExecutionLease(
          "parent-lease",
          lease.copy(heartbeatAt = "2026-08-02T10:00:10Z", expiresAt = "2026-08-02T10:00:40Z"),
        ),
      )
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalRunnerControlStore(connection)
      assertEquals("2026-08-02T10:00:40Z", store.executionLease("parent-lease")?.expiresAt)
      store.persistControlState(
        "parent-lease",
        GoalRunnerControlState(
          pauseRequested = true,
          pauseReason = "operator_request",
          executionLease = requireNotNull(store.executionLease("parent-lease")),
        ),
      )
      store.clearControlState("parent-lease")
      assertEquals(
        GoalRunnerControlState(
          executionLease = lease.copy(heartbeatAt = "2026-08-02T10:00:10Z", expiresAt = "2026-08-02T10:00:40Z"),
        ),
        store.controlState("parent-lease"),
      )
      assertTrue(store.releaseExecutionLease("parent-lease", lease.ownerToken, lease.generation))
      assertEquals(null, store.executionLease("parent-lease"))
    }
  }
}

package skillbill.infrastructure.sqlite.workflow

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import skillbill.error.AuditRepairCycleConflictError
import skillbill.error.InvalidAuditRepairCycleSchemaError
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AuditRepairAssessment
import skillbill.workflow.taskruntime.model.AuditRepairCheckpoint
import skillbill.workflow.taskruntime.model.AuditRepairCriterion
import skillbill.workflow.taskruntime.model.AuditRepairCycle
import skillbill.workflow.taskruntime.model.AuditRepairCycleCodec
import skillbill.workflow.taskruntime.model.AuditRepairIdentity
import skillbill.workflow.taskruntime.model.AuditRepairLaunchBinding
import skillbill.workflow.taskruntime.model.AuditRepairOutcome
import skillbill.workflow.taskruntime.model.AuditRepairRevision
import skillbill.workflow.taskruntime.model.AuditRepairStage
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AuditRepairCycleStoreTest {
  @TempDir
  lateinit var temporary: Path

  @Test
  fun `failed diagnosis insertion leaves no cycle and retry after reopen preserves original evidence`() {
    open().use { connection ->
      initialize(connection)
      connection.createStatement().use {
        it.execute(
          """
          CREATE TRIGGER reject_diagnosis BEFORE INSERT ON audit_repair_cycle_revisions
          BEGIN SELECT RAISE(ABORT, 'injected diagnosis write failure'); END
          """.trimIndent(),
        )
      }
      assertFailsWith<SQLException> { AuditRepairCycleStore(connection, clock).start(diagnosis) }
    }
    open().use { connection ->
      val store = AuditRepairCycleStore(connection, clock)
      assertNull(store.findForAttempt(identity.workflowId, identity.auditAttempt))
      connection.createStatement().use { it.execute("DROP TRIGGER reject_diagnosis") }
      assertEquals(diagnosis, store.start(diagnosis))
      val replacement = diagnosis.copy(
        revisions = listOf(
          diagnosis.current.copy(assessment = diagnosis.diagnosis.copy(value = "Rewritten evidence.")),
        ),
      )
      assertFailsWith<AuditRepairCycleConflictError> { store.start(replacement) }
    }
    open().use { connection ->
      assertEquals(diagnosis, AuditRepairCycleStore(connection, clock).findForAttempt(identity.workflowId, 1))
    }
  }

  @Test
  fun `failed stage update rolls back evidence and permits exact retry after reopen`() {
    open().use { connection ->
      initialize(connection)
      val store = AuditRepairCycleStore(connection, clock)
      assertEquals(diagnosis, store.start(diagnosis))
      connection.createStatement().use {
        it.execute(
          """
          CREATE TRIGGER reject_stage_update BEFORE UPDATE ON audit_repair_cycles
          BEGIN SELECT RAISE(ABORT, 'injected failure after evidence insertion'); END
          """.trimIndent(),
        )
      }
      assertFailsWith<SQLException> { store.advance(identity, 0, authorization) }
    }
    open().use { connection ->
      val store = AuditRepairCycleStore(connection, clock)
      assertEquals(diagnosis, store.find(identity.workflowId, identity.cycleId))
      assertEquals(diagnosis, store.findForAttempt(identity.workflowId, identity.auditAttempt))
      assertNull(store.findForAttempt(identity.workflowId, identity.auditAttempt + 1))
      connection.createStatement().use { it.execute("DROP TRIGGER reject_stage_update") }
      val authorized = store.advance(identity, 0, authorization)
      assertEquals(diagnosis.diagnosis, authorized.diagnosis)
      assertEquals(authorized, store.advance(identity, 0, authorization))
      assertEquals(1, authorized.repairRoundCount)
      assertFailsWith<AuditRepairCycleConflictError> {
        store.advance(identity, 0, authorization.copy(repositoryFingerprint = "other-tree"))
      }
      assertEquals(authorized, store.find(identity.workflowId, identity.cycleId))
    }
  }

  @Test
  fun `wrong session stale owner wrong attempt and expired lease cannot authorize repair`() {
    open().use { connection ->
      initialize(connection)
      val store = AuditRepairCycleStore(connection, clock)
      store.start(diagnosis)
      listOf(
        identity.copy(sessionId = "unrelated-session"),
        identity.copy(executionId = "unrelated-execution"),
        identity.copy(ownerToken = "replaced-owner"),
        identity.copy(fencingGeneration = 2),
        identity.copy(auditAttempt = 2),
      ).forEach { stale ->
        assertFailsWith<AuditRepairCycleConflictError> { store.advance(stale, 0, authorization) }
        assertEquals(diagnosis, store.find(identity.workflowId, identity.cycleId))
      }
      val expired = AuditRepairCycleStore(connection, Clock.offset(clock, Duration.ofHours(2)))
      assertFailsWith<AuditRepairCycleConflictError> { expired.advance(identity, 0, authorization) }
      assertEquals(diagnosis, store.find(identity.workflowId, identity.cycleId))
    }
  }

  @Test
  fun `new cycle identity cannot replace the diagnosis for the same audit attempt`() {
    open().use { connection ->
      initialize(connection)
      val store = AuditRepairCycleStore(connection, clock)
      store.start(diagnosis)
      val authorized = store.advance(identity, 0, authorization)
      assertEquals(authorized, store.start(diagnosis))
      val replacement = diagnosis.copy(identity = identity.copy(cycleId = "replacement-cycle"))
      assertFailsWith<AuditRepairCycleConflictError> { store.start(replacement) }
      assertNull(store.find(identity.workflowId, replacement.identity.cycleId))
    }
    open().use { connection ->
      val restored = AuditRepairCycleStore(connection, clock).find(identity.workflowId, identity.cycleId)
      assertEquals(diagnosis.diagnosis, restored?.diagnosis)
      assertEquals(1, restored?.repairRoundCount)
    }
  }

  @Test
  fun `terminal workflow with surviving lease cannot create or advance audit evidence`() {
    open().use { connection ->
      initialize(connection)
      val store = AuditRepairCycleStore(connection, clock)
      store.start(diagnosis)
      connection.createStatement().use {
        it.execute("UPDATE feature_task_workflows SET workflow_status = 'completed'")
      }
      assertFailsWith<AuditRepairCycleConflictError> { store.advance(identity, 0, authorization) }
      val replacement = diagnosis.copy(identity = identity.copy(cycleId = "replacement-cycle"))
      assertFailsWith<AuditRepairCycleConflictError> { store.start(replacement) }
      assertNull(store.find(identity.workflowId, replacement.identity.cycleId))
    }
    open().use { connection ->
      assertEquals(diagnosis, AuditRepairCycleStore(connection, clock).find(identity.workflowId, identity.cycleId))
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT workflow_status FROM feature_task_workflows").use {
          it.next()
          assertEquals("completed", it.getString(1))
        }
      }
    }
  }

  @Test
  fun `reopen rejects a latest snapshot that rewrites the committed diagnosis`() {
    open().use { connection ->
      initialize(connection)
      val store = AuditRepairCycleStore(connection, clock)
      store.start(diagnosis)
      val authorized = store.advance(identity, 0, authorization)
      val rewritten = authorized.copy(
        revisions = listOf(
          diagnosis.current.copy(assessment = diagnosis.diagnosis.copy(value = "Replaced diagnosis.")),
          authorization,
        ),
      )
      connection.prepareStatement(
        "UPDATE audit_repair_cycle_revisions SET evidence_json = ? WHERE revision = 1",
      ).use {
        it.setString(1, AuditRepairCycleCodec.encode(rewritten))
        it.executeUpdate()
      }
    }
    open().use { connection ->
      val store = AuditRepairCycleStore(connection, clock)
      assertFailsWith<InvalidAuditRepairCycleSchemaError> { store.find(identity.workflowId, identity.cycleId) }
      assertFailsWith<InvalidAuditRepairCycleSchemaError> {
        store.findForAttempt(identity.workflowId, identity.auditAttempt)
      }
      assertFailsWith<InvalidAuditRepairCycleSchemaError> { store.advance(identity, 0, authorization) }
    }
  }

  @Test
  fun `orphaned diagnosis cannot be mistaken for a legacy attempt without a cycle after reopen`() {
    open().use { connection ->
      initialize(connection)
      AuditRepairCycleStore(connection, clock).start(diagnosis)
      connection.createStatement().use { it.execute("DELETE FROM audit_repair_cycles") }
    }
    open().use { connection ->
      val store = AuditRepairCycleStore(connection, clock)
      assertFailsWith<InvalidAuditRepairCycleSchemaError> {
        store.find(identity.workflowId, identity.cycleId)
      }
      assertFailsWith<InvalidAuditRepairCycleSchemaError> {
        store.findForAttempt(identity.workflowId, identity.auditAttempt)
      }
      assertFailsWith<InvalidAuditRepairCycleSchemaError> { store.start(diagnosis) }
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT evidence_json FROM audit_repair_cycle_revisions").use {
          it.next()
          assertEquals(diagnosis, AuditRepairCycleCodec.decode(it.getString(1)))
        }
      }
      assertNull(store.findForAttempt("unrelated-workflow", identity.auditAttempt))
    }
  }

  @Test
  fun `valid cycle cannot hide orphaned evidence from another attempt after reopen`() {
    val orphan = diagnosis.copy(identity = identity.copy(cycleId = "orphan-cycle", auditAttempt = 2))
    open().use { connection ->
      initialize(connection)
      AuditRepairCycleStore(connection, clock).start(diagnosis)
      connection.prepareStatement(
        "INSERT INTO audit_repair_cycle_revisions VALUES (?, ?, ?, ?)",
      ).use {
        it.setString(1, orphan.identity.workflowId)
        it.setString(2, orphan.identity.cycleId)
        it.setInt(3, orphan.current.revision)
        it.setString(4, AuditRepairCycleCodec.encode(orphan))
        it.executeUpdate()
      }
    }
    open().use { connection ->
      val store = AuditRepairCycleStore(connection, clock)
      assertFailsWith<InvalidAuditRepairCycleSchemaError> {
        store.find(identity.workflowId, identity.cycleId)
      }
      assertFailsWith<InvalidAuditRepairCycleSchemaError> {
        store.findForAttempt(identity.workflowId, identity.auditAttempt)
      }
      assertFailsWith<InvalidAuditRepairCycleSchemaError> { store.advance(identity, 0, authorization) }
      assertFailsWith<InvalidAuditRepairCycleSchemaError> { store.start(diagnosis) }
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT revision FROM audit_repair_cycles").use {
          it.next()
          assertEquals(0, it.getInt(1))
        }
        statement.executeQuery("SELECT COUNT(*) FROM audit_repair_cycle_revisions").use {
          it.next()
          assertEquals(2, it.getInt(1))
        }
      }
      assertNull(store.findForAttempt("unrelated-workflow", identity.auditAttempt))
    }
  }

  @Test
  fun `reopen rejects missing history even when the current snapshot contains it`() {
    open().use { connection ->
      initialize(connection)
      val store = AuditRepairCycleStore(connection, clock)
      store.start(diagnosis)
      store.advance(identity, 0, authorization)
      connection.createStatement().use {
        it.execute("DELETE FROM audit_repair_cycle_revisions WHERE revision = 0")
      }
    }
    open().use { connection ->
      assertFailsWith<InvalidAuditRepairCycleSchemaError> {
        AuditRepairCycleStore(connection, clock).find(identity.workflowId, identity.cycleId)
      }
    }
  }

  @Test
  fun `paused repair receipt survives reopen without inventing a checkpoint or authorizing replay`() {
    val assessment = diagnosis.diagnosis.copy(
      criteria = diagnosis.diagnosis.criteria +
        AuditRepairCriterion("AC-002", false, "Second gap.", "second-repair", "Repair the second gap."),
    )
    val initial = diagnosis.copy(
      criterionRefs = listOf("AC-001", "AC-002"),
      revisions = listOf(diagnosis.current.copy(assessment = assessment)),
    )
    val outcome = AuditRepairOutcome(
      "repair",
      "Repair attempted but repository progress is unproven.",
      listOf("src/Repair.kt"),
    )
    val pause = AuditRepairRevision(
      revision = 2,
      requestId = "paused-repair-request",
      stage = AuditRepairStage.PAUSED,
      recordedAt = clock.instant().toString(),
      repairOutcomes = listOf(outcome),
      reason = "Repository progress is unproven; operator action is required.",
    )
    open().use { connection ->
      initialize(connection)
      val store = AuditRepairCycleStore(connection, clock)
      store.start(initial)
      val authorized = store.advance(identity, 0, authorization)
      listOf(
        listOf(outcome.copy(repairId = "unrelated-repair")),
        listOf(outcome, outcome),
        listOf(outcome.copy(changedPaths = listOf("../Other.kt"))),
      ).forEach { invalidOutcomes ->
        assertFailsWith<InvalidAuditRepairCycleSchemaError> {
          store.advance(identity, 1, pause.copy(repairOutcomes = invalidOutcomes))
        }
        assertEquals(authorized, store.findForAttempt(identity.workflowId, identity.auditAttempt))
      }
      store.advance(identity, 1, pause)
    }
    open().use { connection ->
      val store = AuditRepairCycleStore(connection, clock)
      val restored = requireNotNull(store.findForAttempt(identity.workflowId, identity.auditAttempt))
      assertEquals(initial.diagnosis, restored.diagnosis)
      assertEquals(listOf(outcome), restored.current.repairOutcomes)
      assertEquals(pause.reason, restored.current.reason)
      assertNull(restored.current.checkpoint)
      assertNull(restored.finalAssessment)
      assertEquals(restored, store.advance(identity, 1, pause))
      assertFailsWith<InvalidAuditRepairCycleSchemaError> {
        store.advance(identity, 2, authorization.copy(revision = 3, requestId = "unapproved-retry"))
      }
      assertEquals(restored, store.findForAttempt(identity.workflowId, identity.auditAttempt))
    }
  }

  @Test
  fun `checkpoint attachment rollback retains repair intent and final audit survives reopen before settlement`() {
    val after = completedCheckpoint
    val pending = retainedRepairIntent
    val attachment = retainedAttachment
    val final = satisfiedRevision
    open().use { connection ->
      initialize(connection)
      val store = AuditRepairCycleStore(connection, clock)
      store.start(diagnosis)
      store.advance(identity, 0, authorization)
      store.advance(identity, 1, pending)
      connection.createStatement().use {
        it.execute(
          """
          CREATE TRIGGER reject_checkpoint_attachment BEFORE UPDATE ON audit_repair_cycles
          WHEN NEW.revision = 3
          BEGIN SELECT RAISE(ABORT, 'injected checkpoint attachment failure'); END
          """.trimIndent(),
        )
      }
      assertFailsWith<SQLException> { store.advance(identity, 2, attachment) }
    }
    open().use { connection ->
      val store = AuditRepairCycleStore(connection, clock)
      val restored = requireNotNull(store.findForAttempt(identity.workflowId, identity.auditAttempt))
      assertEquals(pending, restored.current)
      assertEquals(diagnosis.diagnosis, restored.diagnosis)
      assertNull(restored.finalAssessment)
      assertEquals(restored, store.advance(identity, 1, pending))
      connection.createStatement().use { it.execute("DROP TRIGGER reject_checkpoint_attachment") }
      val attached = store.advance(identity, 2, attachment)
      assertEquals(attached, store.advance(identity, 2, attachment))
      assertEquals(1, attached.repairRoundCount)
      assertEquals(pending.repairOutcomes, attached.revisions[2].repairOutcomes)
      store.advance(identity, 3, final)
    }
    open().use { connection ->
      val store = AuditRepairCycleStore(connection, clock)
      val restored = requireNotNull(store.findForAttempt(identity.workflowId, identity.auditAttempt))
      assertEquals(diagnosis.diagnosis, restored.diagnosis)
      assertEquals(after, restored.revisions[3].checkpoint)
      assertEquals(final.assessment, restored.finalAssessment)
      assertEquals(restored, store.advance(identity, 3, final))
      assertEquals(5, restored.revisions.size)
      assertFailsWith<AuditRepairCycleConflictError> {
        store.advance(identity, 2, attachment.copy(checkpoint = after.copy(checkpointId = "replacement")))
      }
      assertEquals(restored, store.findForAttempt(identity.workflowId, identity.auditAttempt))
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT workflow_status, current_step_id FROM feature_task_workflows").use {
          it.next()
          assertEquals("running", it.getString(1))
          assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT, it.getString(2))
        }
      }
    }
  }

  @Test
  fun `checkpoint intent survives reopen before fenced attachment and rejects conflicting content`() {
    val pendingWithoutAttachment = retainedRepairIntent.copy(checkpoint = null)
    open().use { connection ->
      initialize(connection)
      val store = AuditRepairCycleStore(connection, clock)
      store.start(diagnosis)
      store.advance(identity, 0, authorization)
      val pending = store.advance(identity, 1, pendingWithoutAttachment)
      assertNull(pending.current.checkpoint)
    }
    open().use { connection ->
      val store = AuditRepairCycleStore(connection, clock)
      val reopened = requireNotNull(store.find(identity.workflowId, identity.cycleId))
      assertEquals(pendingWithoutAttachment, reopened.current)
      val attached = store.attachCheckpoint(identity, 2, "post-repair-intent", completedCheckpoint)
      assertEquals(completedCheckpoint, attached.current.checkpoint)
      assertEquals(attached, store.attachCheckpoint(identity, 2, "post-repair-intent", completedCheckpoint))
      assertFailsWith<AuditRepairCycleConflictError> {
        store.attachCheckpoint(identity, 2, "post-repair-intent", completedCheckpoint.copy(checkpointId = "other"))
      }
      assertEquals(attached, store.find(identity.workflowId, identity.cycleId))
    }
  }

  @Test
  fun `failed final audit reopens with both checkpoints and cannot publish satisfaction`() {
    val after = AuditRepairCheckpoint("after-checkpoint", "after-tree")
    val failed = diagnosis.diagnosis.copy(checkpoint = after, value = "The repair left the criterion unmet.")
    val pause = AuditRepairRevision(
      revision = 4,
      requestId = "final-audit-request",
      stage = AuditRepairStage.PAUSED,
      recordedAt = clock.instant().toString(),
      assessment = failed,
      reason = "Final audit requires operator action.",
    )
    open().use { connection ->
      initialize(connection)
      val store = AuditRepairCycleStore(connection, clock)
      store.start(diagnosis)
      store.advance(identity, 0, authorization)
      store.advance(identity, 1, retainedRepairIntent)
      store.advance(identity, 2, retainedAttachment)
      store.advance(identity, 3, pause)
    }
    open().use { connection ->
      val store = AuditRepairCycleStore(connection, clock)
      val restored = requireNotNull(store.find(identity.workflowId, identity.cycleId))
      assertEquals(restored, store.findForAttempt(identity.workflowId, identity.auditAttempt))
      assertEquals(diagnosis.diagnosis, restored.diagnosis)
      assertEquals(after, restored.revisions[3].checkpoint)
      assertEquals(failed, restored.current.assessment)
      assertEquals(pause.reason, restored.current.reason)
      assertEquals(1, restored.repairRoundCount)
      assertNull(restored.finalAssessment)
      assertEquals(restored, store.advance(identity, 3, pause))
      assertFailsWith<AuditRepairCycleConflictError> {
        store.advance(
          identity,
          4,
          authorization.copy(
            revision = 5,
            requestId = "unauthorized-retry",
            repositoryFingerprint = after.repositoryFingerprint,
          ),
        )
      }
      assertEquals(restored, store.find(identity.workflowId, identity.cycleId))
    }
  }

  private val completedCheckpoint get() = AuditRepairCheckpoint("after-checkpoint", "after-tree")
  private val retainedRepairIntent get() = AuditRepairRevision(
    revision = 2,
    requestId = "checkpoint-intent-request",
    stage = AuditRepairStage.CHECKPOINT_PENDING,
    recordedAt = clock.instant().toString(),
    repositoryFingerprint = completedCheckpoint.repositoryFingerprint,
    checkpoint = completedCheckpoint,
    checkpointIntent = "post-repair-intent",
    repairOutcomes = listOf(AuditRepairOutcome("repair", "Applied scoped repair.", listOf("src/Repair.kt"))),
  )
  private val retainedAttachment get() = AuditRepairRevision(
    revision = 3,
    requestId = "checkpoint-attachment-request",
    stage = AuditRepairStage.FINAL_AUDIT,
    recordedAt = clock.instant().toString(),
    checkpoint = completedCheckpoint,
  )
  private val satisfiedRevision get() = AuditRepairRevision(
    revision = 4,
    requestId = "final-audit-request",
    stage = AuditRepairStage.SATISFIED,
    recordedAt = clock.instant().toString(),
    assessment = AuditRepairAssessment(
      checkpoint = completedCheckpoint,
      criteria = listOf(AuditRepairCriterion("AC-001", true, "Scoped repair satisfies the criterion.")),
      value = "Final read-only repository assessment.",
    ),
  )

  private fun open(): Connection = DriverManager.getConnection("jdbc:sqlite:${temporary.resolve("audit.db")}")

  private fun initialize(connection: Connection) {
    AuditRepairCycleMigration.apply(connection)
    AuditRepairLaunchBindingMigration.apply(connection)
    connection.createStatement().use {
      it.execute(
        "CREATE TABLE telemetry_outbox (id INTEGER PRIMARY KEY, event_name " +
          "TEXT, payload_json TEXT, skill_bill_version TEXT)",
      )
      it.execute(
        """
        CREATE TABLE feature_task_workflows (
          workflow_id TEXT PRIMARY KEY, mode TEXT NOT NULL, workflow_status TEXT NOT NULL,
          current_step_id TEXT NOT NULL, artifacts_json TEXT NOT NULL DEFAULT '{}', active_audit_cycle_id TEXT
        )
        """.trimIndent(),
      )
      it.execute(
        """
        CREATE TABLE feature_task_runtime_worker_leases (
          workflow_id TEXT PRIMARY KEY, contract_version TEXT NOT NULL, generation INTEGER NOT NULL,
          owner_token TEXT NOT NULL, host_identity TEXT NOT NULL, boot_identity TEXT NOT NULL,
          pid INTEGER NOT NULL, process_birth_token TEXT NOT NULL, lease_state TEXT NOT NULL,
          heartbeat_at TEXT NOT NULL, expires_at TEXT NOT NULL, phase_id TEXT NOT NULL, phase_attempt INTEGER NOT NULL
        )
        """.trimIndent(),
      )
    }
    connection.prepareStatement(
      "INSERT INTO feature_task_workflows (workflow_id, mode, " +
        "workflow_status, current_step_id) VALUES (?, 'runtime', 'running', ?)",
    ).use {
      it.setString(1, identity.workflowId)
      it.setString(2, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
      it.executeUpdate()
    }
    connection.insertWorkerOwnership(
      FeatureTaskRuntimeWorkerOwnership(
        workflowId = identity.workflowId,
        generation = identity.fencingGeneration,
        ownerToken = identity.ownerToken,
        hostIdentity = "host",
        bootIdentity = "boot",
        pid = 1,
        processBirthToken = "birth",
        leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
        heartbeatAt = clock.instant().toString(),
        expiresAt = clock.instant().plusSeconds(3600).toString(),
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        phaseAttempt = identity.auditAttempt,
      ),
    )
    bindLaunch(connection)
  }

  private fun bindLaunch(connection: Connection) {
    val store = AuditRepairCycleStore(connection, clock)
    store.bindLaunch(
      AuditRepairLaunchBinding(
        identity.workflowId, identity.auditAttempt, identity.cycleId, identity.executionId, identity.sessionId,
        identity.ownerToken, identity.fencingGeneration, boundAt = clock.instant().toString(),
        checkpoint = diagnosis.diagnosis.checkpoint,
      ),
    )
    store.recordProviderSession(identity, "provider-session")
  }

  private val clock = Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC)
  private val identity =
    AuditRepairIdentity("workflow", 1, "execution", "session", "cycle", "audit-worker-owner-token", 1)
  private val diagnosis = AuditRepairCycle(
    identity = identity,
    criterionRefs = listOf("AC-001"),
    revisions = listOf(
      AuditRepairRevision(
        revision = 0,
        requestId = "diagnosis-request",
        stage = AuditRepairStage.DIAGNOSIS,
        recordedAt = clock.instant().toString(),
        assessment = AuditRepairAssessment(
          checkpoint = AuditRepairCheckpoint("before-checkpoint", "before-tree"),
          criteria = listOf(AuditRepairCriterion("AC-001", false, "Gap evidence.", "repair", "Repair this gap.")),
          value = "Initial diagnosis.",
        ),
      ),
    ),
  )
  private val authorization = AuditRepairRevision(
    revision = 1,
    requestId = "authorization-request",
    stage = AuditRepairStage.AUTHORIZED_REPAIR,
    recordedAt = clock.instant().toString(),
    repositoryFingerprint = "before-tree",
  )
}

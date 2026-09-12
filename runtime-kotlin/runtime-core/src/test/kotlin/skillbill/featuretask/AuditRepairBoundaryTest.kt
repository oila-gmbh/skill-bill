package skillbill.featuretask

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.AuditRepairCycleKeys
import skillbill.engine.featuretask.FeatureTaskPhaseSettlementService
import skillbill.engine.featuretask.FeatureTaskRuntimeAcceptanceCriteriaSource
import skillbill.engine.featuretask.GitAuditRepairCheckpointCoordinator
import skillbill.engine.featuretask.model.FeatureTaskPhaseSettlementAuditRequest
import skillbill.error.AuditRepairCycleConflictError
import skillbill.infrastructure.fs.GitWorkflowGitOperations
import skillbill.infrastructure.sqlite.SQLiteDatabaseSessionFactory
import skillbill.infrastructure.sqlite.SqliteFeatureTaskPhaseSettlementRepository
import skillbill.infrastructure.sqlite.core.DatabaseRuntime
import skillbill.infrastructure.sqlite.workflow.SqliteAuditRepairCycleRepository
import skillbill.infrastructure.sqlite.workflow.WorkflowStateStore
import skillbill.model.EnvironmentContext
import skillbill.model.RepositoryRoot
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX
import skillbill.workflow.taskruntime.model.AuditRepairAssessment
import skillbill.workflow.taskruntime.model.AuditRepairCheckpoint
import skillbill.workflow.taskruntime.model.AuditRepairCriterion
import skillbill.workflow.taskruntime.model.AuditRepairIdentity
import skillbill.workflow.taskruntime.model.AuditRepairOutcome
import skillbill.workflow.taskruntime.model.AuditRepairRevision
import skillbill.workflow.taskruntime.model.AuditRepairStage
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_AUDIT_GAP_PAUSE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskAuditRepairStageRequest
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuditRepairBoundaryTest {
  @TempDir lateinit var temporary: Path
  private val clock = Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC)
  private val identity = AuditRepairIdentity("workflow", 1, "execution", "session", "cycle", "worker-owner-token", 1)
  private val gitOperations = GitWorkflowGitOperations()
  private lateinit var repo: Path
  private lateinit var database: SQLiteDatabaseSessionFactory
  private lateinit var cycles: SqliteAuditRepairCycleRepository
  private lateinit var service: FeatureTaskPhaseSettlementService
  private lateinit var initial: AuditRepairCheckpoint

  @BeforeEach
  fun prepare() {
    repo = Files.createDirectory(temporary.resolve("repo"))
    git("init", "-b", "feature")
    git("config", "user.email", "audit@example.test")
    git("config", "user.name", "Audit test")
    git("config", "commit.gpgsign", "false")
    repo.resolve("owned.txt").writeText("base")
    repo.resolve("other.txt").writeText("unrelated base")
    git("add", ".")
    git("commit", "-m", "base")
    repo.resolve("owned.txt").writeText("implemented")
    repo.resolve("other.txt").writeText("unrelated staged")
    git("add", "other.txt")
    database = SQLiteDatabaseSessionFactory(
      EnvironmentContext(
        dbPathOverride = temporary.resolve("audit.db").toString(),
        environment = emptyMap(),
      ),
    )
    seedWorkflow()
    cycles = SqliteAuditRepairCycleRepository(database, clock)
    val criteria = object : FeatureTaskRuntimeAcceptanceCriteriaSource {
      override fun cycleRequired(workflowId: String): Boolean = true
      override fun criterionRefs(workflowId: String): List<String> = listOf("AC-001")
    }
    val diagnostics = object : RuntimeDiagnostics {
      override fun warning(message: String, error: Throwable?) = Unit
      override fun error(message: String, error: Throwable?) = Unit
    }
    service = FeatureTaskPhaseSettlementService(
      SqliteFeatureTaskPhaseSettlementRepository(database),
      clock,
      cycles,
      criteria,
      GitAuditRepairCheckpointCoordinator(gitOperations, RepositoryRoot(repo)),
      diagnostics,
    )
    initial = service.prepareAuditCheckpoint(identity.cycleId, fingerprint(), listOf("owned.txt"))
    service.bindAuditRepairLaunch(identity, initial)
  }

  @Test
  fun `initially satisfied dirty implementation survives settlement and staging changes`() {
    val head = git("rev-parse", "HEAD")
    val index = git("write-tree")
    assertFailsWith<AuditRepairCycleConflictError> { stage(diagnosis(satisfied = true)) }
    cycles.recordProviderSession(identity, "provider")
    cycles.recordProviderSession(identity, "provider")
    assertFailsWith<AuditRepairCycleConflictError> { cycles.recordProviderSession(identity, "replacement") }
    stage(diagnosis(satisfied = true))
    val pending = revision(1, AuditRepairStage.CHECKPOINT_PENDING).copy(
      repositoryFingerprint = fingerprint(),
      checkpointIntent = "retain",
      repairOutcomes = emptyList(),
    )
    rejectOutboxWriteThenReplay(pending)
    val retained = stage(pending)[AuditRepairCycleKeys.CHECKPOINT_ID] as String
    assertEquals("implemented", git("show", "$retained:owned.txt"))
    assertEquals("unrelated base", git("show", "$retained:other.txt"))
    assertEquals(head, git("rev-parse", "HEAD"))
    assertEquals(index, git("write-tree"))
    val checkpoint = assertNotNull(cycles.findActive(identity.workflowId)?.current?.checkpoint)
    stage(revision(2, AuditRepairStage.FINAL_AUDIT).copy(checkpoint = checkpoint))
    val final = AuditRepairAssessment(checkpoint, listOf(criterion(true)), "Final repository proof")
    stage(revision(3, AuditRepairStage.SATISFIED).copy(assessment = final))
    assertEquals(1, stage(pending)[AuditRepairCycleKeys.REVISION])
    git("reset", "HEAD", "other.txt")
    assertTrue(service.auditRepairReady(identity.workflowId, 1, final.value))
    service.auditSettle(
      FeatureTaskPhaseSettlementAuditRequest(identity.workflowId, "audit", 1, "satisfied", final.value),
    )
    repo.resolve("owned.txt").writeText("regression")
    assertFalse(service.auditRepairReady(identity.workflowId, 1, final.value))
  }

  @Test
  fun `retry rollback and stale replay cannot consume or reuse operator authorization`() {
    cycles.recordProviderSession(identity, "provider")
    stage(diagnosis(satisfied = false))
    stage(revision(1, AuditRepairStage.AUTHORIZED_REPAIR).copy(repositoryFingerprint = initial.repositoryFingerprint))
    stage(revision(2, AuditRepairStage.PAUSED).copy(reason = "Repair interrupted"))
    val paused = pause()
    assertEquals(1, paused.edgeIteration)
    writePause(paused.copy(operatorDecision = AUDIT_GAP_PAUSE_DECISION_RETRY_FIX))
    repo.resolve("owned.txt").writeText("repaired")
    val pending = revision(3, AuditRepairStage.CHECKPOINT_PENDING).copy(
      repositoryFingerprint = fingerprint(),
      checkpointIntent = "retry-intent",
      repairOutcomes = listOf(AuditRepairOutcome("repair", "Fixed the behavior", listOf("owned.txt"))),
    )
    sql(
      "CREATE TRIGGER reject_revision BEFORE INSERT ON audit_repair_cycle_revisions " +
        "BEGIN SELECT RAISE(ABORT, 'interruption'); END",
    )
    assertFailsWith<SQLException> { stage(pending) }
    assertFalse(pause().grantConsumed)
    assertEquals(AuditRepairStage.PAUSED, cycles.findActive(identity.workflowId)?.current?.stage)
    sql("DROP TRIGGER reject_revision")
    stage(pending)
    assertTrue(pause().grantConsumed)
    val checkpoint = assertNotNull(cycles.findActive(identity.workflowId)?.current?.checkpoint)
    stage(revision(4, AuditRepairStage.FINAL_AUDIT).copy(checkpoint = checkpoint))
    stage(
      revision(5, AuditRepairStage.PAUSED).copy(
        assessment = AuditRepairAssessment(checkpoint, listOf(criterion(false)), "Still unresolved"),
        reason = "AC remains",
      ),
    )
    writePause(pause().copy(operatorDecision = AUDIT_GAP_PAUSE_DECISION_RETRY_FIX))
    assertEquals(3, stage(pending)[AuditRepairCycleKeys.REVISION])
    assertFalse(pause().grantConsumed)
    sql(
      "UPDATE feature_task_runtime_worker_leases SET heartbeat_at = " +
        "'2026-09-12T08:00:00Z', expires_at = '2026-09-12T09:00:00Z'",
    )
    assertFailsWith<AuditRepairCycleConflictError> { stage(pending) }
  }

  @Test
  fun `interrupted diagnosis and attached checkpoints resume without repeating implementation`() {
    cycles.recordProviderSession(identity, "provider")
    stage(diagnosis(satisfied = true))
    stage(revision(1, AuditRepairStage.PAUSED).copy(reason = "Interrupted after diagnosis."))
    writePause(pause().copy(operatorDecision = AUDIT_GAP_PAUSE_DECISION_RETRY_FIX))
    stage(
      revision(2, AuditRepairStage.CHECKPOINT_PENDING).copy(
        checkpointIntent = "initially-ready",
        repairOutcomes = emptyList(),
      ),
    )
    val retained = assertNotNull(cycles.findActive(identity.workflowId)?.latestCheckpoint)
    stage(revision(3, AuditRepairStage.PAUSED).copy(reason = "Interrupted before attachment."))
    writePause(pause().copy(operatorDecision = AUDIT_GAP_PAUSE_DECISION_RETRY_FIX))
    stage(revision(4, AuditRepairStage.FINAL_AUDIT))
    stage(revision(5, AuditRepairStage.PAUSED).copy(reason = "Interrupted during final assessment."))
    writePause(pause().copy(operatorDecision = AUDIT_GAP_PAUSE_DECISION_RETRY_FIX))
    stage(revision(6, AuditRepairStage.FINAL_AUDIT))
    val final = AuditRepairAssessment(retained, listOf(criterion(true)), "Restored final assessment")
    stage(revision(7, AuditRepairStage.SATISFIED).copy(assessment = final))
    val restored = assertNotNull(cycles.statusSnapshot(identity.workflowId)?.cycle)
    assertEquals(0, restored.repairRoundCount)
    assertEquals(retained, restored.finalAssessment?.checkpoint)
    assertEquals("implemented", git("show", "${retained.checkpointId}:owned.txt"))
    assertTrue(service.auditRepairReady(identity.workflowId, 1, final.value))
  }

  @Test
  fun `unchanged repair content pauses and another receipt cannot create a repair loop`() {
    cycles.recordProviderSession(identity, "provider")
    stage(diagnosis(satisfied = false))
    stage(revision(1, AuditRepairStage.AUTHORIZED_REPAIR).copy(repositoryFingerprint = initial.repositoryFingerprint))
    val receipt = revision(2, AuditRepairStage.CHECKPOINT_PENDING).copy(
      checkpointIntent = "unchanged",
      repairOutcomes = listOf(AuditRepairOutcome("repair", "Claimed a repair", listOf("owned.txt"))),
    )
    val paused = stage(receipt)
    assertEquals(AuditRepairStage.PAUSED.wireValue, paused[AuditRepairCycleKeys.STAGE])
    assertEquals(1, pause().edgeIteration)
    assertFailsWith<AuditRepairCycleConflictError> { stage(receipt) }
    assertEquals(2, cycles.findActive(identity.workflowId)?.current?.revision)
    assertEquals(3, eventCount())
  }

  private fun seedWorkflow() = DatabaseRuntime.openDbAt(database.resolveDbPath()).use { db ->
    val store = WorkflowStateStore(db.connection)
    store.saveFeatureTaskRuntimeWorkflow(
      WorkflowStateRecord(
        identity.workflowId, identity.sessionId, "feature-task-runtime",
        FeatureTaskRuntimePhaseWorkflowDefinition.definition.contractVersion,
        "running", "audit", "[]", "{}", clock.instant().toString(), null, null,
      ),
    )
    assertTrue(
      store.acquireFeatureTaskRuntimeWorker(
        FeatureTaskRuntimeWorkerOwnership(
          identity.workflowId, identity.fencingGeneration, identity.ownerToken, "host", "boot", 1, "birth",
          FeatureTaskRuntimeWorkerLeaseState.ACTIVE, clock.instant().toString(),
          clock.instant().plusSeconds(3600).toString(), "preplan", 1,
        ),
        store.getFeatureTaskRuntimeWorkflow(identity.workflowId)?.updatedAt,
      ),
    )
  }

  private fun diagnosis(satisfied: Boolean) = revision(0, AuditRepairStage.DIAGNOSIS).copy(
    assessment = AuditRepairAssessment(initial, listOf(criterion(satisfied)), "Repository diagnosis"),
  )

  private fun criterion(satisfied: Boolean) = AuditRepairCriterion(
    "AC-001",
    satisfied,
    "Evidence from owned.txt",
    "repair".takeUnless { satisfied },
    "Correct owned.txt".takeUnless { satisfied },
  )

  private fun revision(number: Int, stage: AuditRepairStage) = AuditRepairRevision(
    number,
    "request-$number",
    stage,
    clock.instant().toString(),
  )

  private fun rejectOutboxWriteThenReplay(pending: AuditRepairRevision) {
    sql(
      """
      CREATE TRIGGER reject_audit_event BEFORE INSERT ON telemetry_outbox
      BEGIN SELECT RAISE(ABORT, 'injected outbox failure'); END
      """.trimIndent(),
    )
    assertFailsWith<SQLException> { stage(pending) }
    assertEquals(0, cycles.statusSnapshot(identity.workflowId)?.cycle?.current?.revision)
    assertEquals(1, eventCount())
    sql("DROP TRIGGER reject_audit_event")
    val acknowledgement = stage(pending)
    assertEquals(2, eventCount())
    assertEquals(acknowledgement, stage(pending))
    assertEquals(2, eventCount())
  }

  private fun eventCount(): Int = database.read { unit -> unit.telemetryOutbox.listPending().size }

  private fun stage(revision: AuditRepairRevision): Map<String, Any?> = service.auditStage(
    FeatureTaskAuditRepairStageRequest(identity, (revision.revision - 1).coerceAtLeast(0), revision, listOf("AC-001")),
  )

  private fun fingerprint(): String = gitOperations.repositoryFingerprint(repo).value

  private fun pause(): FeatureTaskRuntimeAuditGapPause = database.read { unit ->
    val row = assertNotNull(unit.workflowStates.getFeatureTaskRuntimeWorkflow(identity.workflowId))
    val artifacts = assertNotNull(JsonCodec.parseObjectOrNull(row.artifactsJson))
    val raw = JsonCodec.jsonElementToValue(assertNotNull(artifacts[FEATURE_TASK_RUNTIME_AUDIT_GAP_PAUSE_ARTIFACT_KEY]))
    FeatureTaskRuntimeAuditGapPause.fromArtifactMap(assertNotNull(JsonCodec.anyToStringAnyMap(raw)))
  }

  private fun writePause(pause: FeatureTaskRuntimeAuditGapPause) = database.transaction { unit ->
    val row = assertNotNull(unit.workflowStates.getFeatureTaskRuntimeWorkflow(identity.workflowId))
    val artifacts = assertNotNull(JsonCodec.parseObjectOrNull(row.artifactsJson))
      .mapValues { JsonCodec.jsonElementToValue(it.value) }.toMutableMap()
    artifacts[FEATURE_TASK_RUNTIME_AUDIT_GAP_PAUSE_ARTIFACT_KEY] = pause.toArtifactMap()
    unit.workflowStates.saveFeatureTaskRuntimeWorkflow(row.copy(artifactsJson = JsonCodec.mapToJsonString(artifacts)))
  }

  private fun sql(sql: String) = DatabaseRuntime.openDbAt(database.resolveDbPath()).use { db ->
    db.connection.createStatement().use { it.execute(sql) }
  }

  private fun git(vararg args: String): String {
    val process = ProcessBuilder(listOf("git") + args).directory(repo.toFile()).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(0, process.waitFor(), output)
    return output.trim()
  }
}

package skillbill.application.featuretask

import skillbill.application.featuretask.model.RuntimeOwnedCommitFocusedAccountingLoadResolution
import skillbill.application.review.toBoundedPayload
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.model.ReviewAccountingRecord
import skillbill.review.context.ReviewTreeAccounting
import skillbill.review.context.model.ReviewAccountingCounters
import skillbill.review.context.model.ReviewAccountingInput
import skillbill.review.context.model.ReviewCommitRoutingAccounting
import skillbill.review.context.model.ReviewIntegrationAccounting
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.GoalSubtaskCommitFocusedAccounting
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FeatureTaskRuntimeRunnerPoliciesTest {
  @Test
  fun `mutating reconciliation passes when runtime checkpoint resolves and matches repository`() {
    assertNull(
      mutatingReconciliationGateReason(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        outputMap = mapOf("status" to "completed"),
        runtimeResolvedCheckpoint = "checkpoint-abc",
        repositoryCheckpoint = "checkpoint-abc",
      ),
    )
  }

  @Test
  fun `mutating reconciliation fails when runtime checkpoint cannot be established`() {
    val reason = mutatingReconciliationGateReason(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      outputMap = mapOf("status" to "completed"),
      runtimeResolvedCheckpoint = null,
      repositoryCheckpoint = null,
    )
    assertNotNull(reason)
    assertEquals(
      "Mutating phase 'implement' could not establish the runtime-resolved repository checkpoint.",
      reason,
    )
  }

  @Test
  fun `mutating reconciliation fails when repository checkpoint mismatches runtime resolution`() {
    val reason = mutatingReconciliationGateReason(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
      outputMap = mapOf("status" to "completed"),
      runtimeResolvedCheckpoint = "runtime-checkpoint",
      repositoryCheckpoint = "stale-agent-checkpoint",
    )
    assertNotNull(reason)
    assertEquals(
      "Mutating phase 'implement_fix' completed with a repository checkpoint mismatch: " +
        "the runtime-resolved checkpoint does not match the repository after the phase.",
      reason,
    )
  }

  @Test
  fun `mutating reconciliation ignores non-mutating phases and non-completed output`() {
    assertNull(
      mutatingReconciliationGateReason(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        outputMap = mapOf("status" to "completed"),
        runtimeResolvedCheckpoint = "a",
        repositoryCheckpoint = "b",
      ),
    )
    assertNull(
      mutatingReconciliationGateReason(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        outputMap = mapOf("status" to "blocked"),
        runtimeResolvedCheckpoint = "a",
        repositoryCheckpoint = "b",
      ),
    )
  }

  @Test
  fun `commit-focused accounting load reports absent sequence when review run id is blank`() {
    val resolution = accountingRecorder().loadRuntimeOwnedCommitFocusedAccounting(
      " ",
      CodeReviewExecutionMode.DELEGATED,
    )
    assertIs<RuntimeOwnedCommitFocusedAccountingLoadResolution.AbsentSequence>(resolution)
    assertEquals("review run id is blank", resolution.cause)
  }

  @Test
  fun `commit-focused accounting load reports absent sequence when no row exists`() {
    val resolution = accountingRecorder().loadRuntimeOwnedCommitFocusedAccounting(
      REVIEW_RUN_ID,
      CodeReviewExecutionMode.DELEGATED,
    )
    assertIs<RuntimeOwnedCommitFocusedAccountingLoadResolution.AbsentSequence>(resolution)
    assertEquals(
      "no persisted accounting row for review run '$REVIEW_RUN_ID'",
      resolution.cause,
    )
  }

  @Test
  fun `commit-focused accounting load reports absent sequence when routing section is missing`() {
    val payload = delegatedAccountingPayload().toMutableMap().apply { remove("commit_routing_accounting") }
    val resolution = accountingRecorder(accountingRecord(payload)).loadRuntimeOwnedCommitFocusedAccounting(
      REVIEW_RUN_ID,
      CodeReviewExecutionMode.DELEGATED,
    )
    assertIs<RuntimeOwnedCommitFocusedAccountingLoadResolution.AbsentSequence>(resolution)
    assertEquals(
      "accounting row has no commit_routing_accounting section",
      resolution.cause,
    )
  }

  @Test
  fun `commit-focused accounting load reports absent sequence when commit count is zero`() {
    val payload = delegatedAccountingPayload().toMutableMap().apply {
      put(
        "commit_routing_accounting",
        mapOf("commit_count" to 0),
      )
    }
    val resolution = accountingRecorder(accountingRecord(payload)).loadRuntimeOwnedCommitFocusedAccounting(
      REVIEW_RUN_ID,
      CodeReviewExecutionMode.DELEGATED,
    )
    assertIs<RuntimeOwnedCommitFocusedAccountingLoadResolution.AbsentSequence>(resolution)
    assertEquals(
      "accounting row has no real commit sequence (commit_count=0)",
      resolution.cause,
    )
  }

  @Test
  fun `commit-focused accounting load reports unreadable when mapping fails`() {
    val payload = delegatedAccountingPayload().toMutableMap().apply {
      @Suppress("UNCHECKED_CAST")
      val routing = (get("commit_routing_accounting") as Map<String, Any?>).toMutableMap()
      routing.remove("commit_sequence_digest")
      put("commit_routing_accounting", routing)
    }
    val resolution = accountingRecorder(accountingRecord(payload)).loadRuntimeOwnedCommitFocusedAccounting(
      REVIEW_RUN_ID,
      CodeReviewExecutionMode.DELEGATED,
    )
    assertIs<RuntimeOwnedCommitFocusedAccountingLoadResolution.Unreadable>(resolution)
    assertEquals(
      "commit-focused accounting row with commit_count=2 could not be mapped",
      resolution.cause,
    )
  }

  @Test
  fun `commit-focused accounting load establishes delegated accounting from persisted row`() {
    val resolution = accountingRecorder(accountingRecord(delegatedAccountingPayload()))
      .loadRuntimeOwnedCommitFocusedAccounting(REVIEW_RUN_ID, CodeReviewExecutionMode.DELEGATED)
    val established = assertIs<RuntimeOwnedCommitFocusedAccountingLoadResolution.Established>(resolution)
    assertEquals(2, established.accounting.commitCount)
    assertEquals("a".repeat(64), established.accounting.commitSequenceDigest)
    assertEquals("completed", established.accounting.integrationTerminalOutcome)
  }

  @Test
  fun `delegated commit-focused accounting resolution blocks unreadable load results`() {
    val resolution = resolveDelegatedCommitFocusedAccounting(
      RuntimeOwnedCommitFocusedAccountingLoadResolution.Unreadable("mapping failed"),
    )
    val blocked = assertIs<DelegatedCommitFocusedAccountingResolution.UnreadableBlocked>(resolution)
    assertEquals(DELEGATED_COMMIT_FOCUSED_ACCOUNTING_UNREADABLE_BLOCK_REASON, blocked.reason)
  }

  @Test
  fun `delegated commit-focused accounting resolution carries established and absent sequences`() {
    val accounting = GoalSubtaskCommitFocusedAccounting(
      commitSequenceDigest = "a".repeat(64),
      commitCount = 2,
      laneCount = 1,
      focusedCommitCount = 2,
      skippedCommitCount = 0,
      integrationTerminalOutcome = "completed",
    )
    val established = assertIs<DelegatedCommitFocusedAccountingResolution.Resolved>(
      resolveDelegatedCommitFocusedAccounting(
        RuntimeOwnedCommitFocusedAccountingLoadResolution.Established(accounting),
      ),
    )
    assertEquals(accounting, established.accounting)

    val absent = assertIs<DelegatedCommitFocusedAccountingResolution.Resolved>(
      resolveDelegatedCommitFocusedAccounting(
        RuntimeOwnedCommitFocusedAccountingLoadResolution.AbsentSequence("no row"),
      ),
    )
    assertNull(absent.accounting)
  }
}

private const val REVIEW_RUN_ID = "rvw-policies-accounting"

private fun delegatedAccountingPayload(): Map<String, Any?> {
  val summary = ReviewTreeAccounting.summarize(
    REVIEW_RUN_ID,
    "packet-digest",
    ReviewAccountingInput(
      lane = "parent",
      assignmentDigest = "assignment-digest",
      counters = ReviewAccountingCounters(10, 20, 30, 1, 2, 3),
      children = listOf(
        ReviewAccountingInput(
          lane = "architecture",
          assignmentDigest = "architecture-digest",
          counters = ReviewAccountingCounters(11, 22, 33, 1, 1, 1),
        ),
      ),
    ),
  ).copy(
    commitRouting = ReviewCommitRoutingAccounting(
      commitSequenceDigest = "a".repeat(64),
      routingDigest = "b".repeat(64),
      commitCount = 2,
      laneCount = 1,
      focusedCommitCount = 2,
      skippedCommitCount = 0,
      focusedPairCount = 2,
      skippedPairCount = 0,
    ),
    integration = ReviewIntegrationAccounting(
      commitSequenceDigest = "a".repeat(64),
      terminalOutcome = "completed",
      summarizedLaneCount = 1,
      findingCount = 0,
      counters = ReviewAccountingCounters(),
    ),
  )
  return summary.toBoundedPayload()
}

private fun accountingRecord(payload: Map<String, Any?>): ReviewAccountingRecord =
  ReviewAccountingRecord(REVIEW_RUN_ID, "packet-digest", payload)

private fun accountingRecorder(vararg records: ReviewAccountingRecord): FeatureTaskRuntimePhaseRecorder {
  val stored = records.associateBy { it.reviewId }.toMutableMap()
  @Suppress("UNCHECKED_CAST")
  val reviews = Proxy.newProxyInstance(
    ReviewRepository::class.java.classLoader,
    arrayOf(ReviewRepository::class.java),
  ) { _, method, args ->
    when (method.name) {
      "saveAccounting" -> {
        val record = args[0] as ReviewAccountingRecord
        stored[record.reviewId] = record
        Unit
      }
      "loadAccounting" -> stored[args[0] as String]
      "fetchFindingVerdicts" -> emptyList<Any>()
      "fetchReviewPassClaims" -> null
      else -> accountingDefaultPortReturn(method)
    }
  } as ReviewRepository
  val database = object : DatabaseSessionFactory {
    private val unitOfWork = object : UnitOfWork {
      override val dbPath: Path = Path.of("/tmp/feature-task-runtime-policies-test.db")
      override val reviews: ReviewRepository = reviews
      override val learnings = error("unused")
      override val lifecycleTelemetry = error("unused")
      override val telemetryReconciliation = error("unused")
      override val telemetryOutbox = error("unused")
      override val workflowStates = error("unused")
      override val workList = skillbill.ports.persistence.EmptyWorkListRepository
      override val goalPlanningPreparations = skillbill.ports.persistence.EmptyGoalPlanningPreparationRepository
      override val featureTaskRuntimeAuditGenerations = error("unused")
      override val rejectedOutputDiagnosticPermissions = error("unused")
      override val rejectedOutputDiagnostics = error("unused")
    }

    override fun resolveDbPath(dbOverride: String?): Path = unitOfWork.dbPath

    override fun databaseExists(dbOverride: String?): Boolean = true

    override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork)

    override fun <T> selfManagedWrite(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork)

    override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork)
  }
  return FeatureTaskRuntimePhaseRecorder(
    database = database,
    workflowSnapshotValidator = testWorkflowSnapshotValidator,
    handoffEnvelopeValidator = AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator,
    handoffFoundationValidator = AcceptingFeatureTaskRuntimeHandoffFoundationValidator,
  )
}

private fun accountingDefaultPortReturn(method: java.lang.reflect.Method): Any? = when {
  method.returnType == Void.TYPE -> null
  List::class.java.isAssignableFrom(method.returnType) -> emptyList<Any>()
  Map::class.java.isAssignableFrom(method.returnType) -> emptyMap<Any, Any>()
  method.returnType == java.lang.Boolean.TYPE -> false
  method.returnType == Integer.TYPE -> 0
  method.returnType == java.lang.Long.TYPE -> 0L
  else -> null
}

package skillbill.application

import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.learning.LearningRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.UnitOfWorkDefaults
import skillbill.ports.review.ReviewRepository
import skillbill.ports.telemetry.LifecycleTelemetryRepository
import skillbill.ports.telemetry.TelemetryOutboxRepository
import skillbill.ports.telemetry.TelemetryReconciliationRepository
import skillbill.ports.work.EmptyWorkListRepository
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.FeatureImplementSessionSummary
import skillbill.ports.workflow.model.FeatureTaskExecutionIdentity
import skillbill.ports.workflow.model.FeatureTaskRouteScope
import skillbill.ports.workflow.model.FeatureTaskWorkflowCandidate
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.ports.workflow.model.FeatureVerifySessionSummary
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.review.model.ReviewFindingVerdict
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.time.Instant

private fun passThroughReviewRepository(): ReviewRepository = Proxy.newProxyInstance(
  ReviewRepository::class.java.classLoader,
  arrayOf(ReviewRepository::class.java),
) { _, method, _ ->
  if (method.name == "fetchFindingVerdicts") {
    emptyList<ReviewFindingVerdict>()
  } else {
    when (method.returnType) {
      Boolean::class.javaPrimitiveType -> false
      Int::class.javaPrimitiveType -> 0
      Long::class.javaPrimitiveType -> 0L
      Void.TYPE -> Unit
      else -> null
    }
  }
} as ReviewRepository

class FakeDatabaseSessionFactory(
  private val workflowStates: WorkflowStateRepository,
  private val fakeDbPath: Path = Path.of("/fake/metrics.db"),
  private val planningPreparations: GoalPlanningPreparationRepository =
    EmptyGoalPlanningPreparationRepository,
  private val goalRunnerControls: GoalRunnerControlRepository =
    EmptyGoalRunnerControlRepository,
) : DatabaseSessionFactory {
  override fun resolveDbPath(): Path = fakeDbPath
  override fun databaseExists(): Boolean = true
  override fun <T> read(block: (UnitOfWork) -> T): T = block(unit())
  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = transaction(block)

  override fun <T> transaction(block: (UnitOfWork) -> T): T = block(unit())

  private fun unit(): UnitOfWork = object : UnitOfWorkDefaults() {
    override val dbPath: Path = fakeDbPath
    override val workflowStates: WorkflowStateRepository = this@FakeDatabaseSessionFactory.workflowStates
    override val learnings: LearningRepository
      get() = error("LearningRepository is not exercised in WorkflowServiceTest.")
    override val reviews: ReviewRepository = passThroughReviewRepository()
    override val lifecycleTelemetry: LifecycleTelemetryRepository
      get() = error("LifecycleTelemetryRepository is not exercised in WorkflowServiceTest.")
    override val telemetryReconciliation: TelemetryReconciliationRepository
      get() = error("TelemetryReconciliationRepository is not exercised in WorkflowServiceTest.")
    override val telemetryOutbox: TelemetryOutboxRepository
      get() = error("TelemetryOutboxRepository is not exercised in WorkflowServiceTest.")
    override val workList = EmptyWorkListRepository
    override val goalPlanningPreparations = planningPreparations
    override val goalRunnerControls = this@FakeDatabaseSessionFactory.goalRunnerControls
  }
}

class InMemoryWorkflowStates : WorkflowStateRepository {
  private val implement = mutableMapOf<String, WorkflowStateRecord>()
  private val verify = mutableMapOf<String, WorkflowStateRecord>()
  private val taskRuntime = mutableMapOf<String, WorkflowStateRecord>()
  private val identities = mutableMapOf<String, FeatureTaskExecutionIdentity>()

  var failSaveWhen: ((WorkflowStateRecord) -> Boolean)? = null

  override fun saveFeatureTaskExecutionIdentity(identity: FeatureTaskExecutionIdentity) {
    val existing = identities.putIfAbsent(identity.workflowId, identity)
    require(existing == null || existing == identity) { "Conflicting immutable identity for '${identity.workflowId}'." }
  }

  override fun getFeatureTaskExecutionIdentity(workflowId: String): FeatureTaskExecutionIdentity? =
    identities[workflowId]

  fun executionIdentity(workflowId: String): FeatureTaskExecutionIdentity? = identities[workflowId]

  fun overwriteExecutionIdentity(identity: FeatureTaskExecutionIdentity) {
    identities[identity.workflowId] = identity
  }

  private fun featureTaskRowsInInsertionOrder(): List<WorkflowStateRecord> =
    (taskRuntime.values + implement.values).distinctBy { it.workflowId }

  override fun findStandaloneFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate> = featureTaskRowsInInsertionOrder()
    .filter { row ->
      row.issueKey?.trim()?.uppercase() == normalizedIssueKey ||
        identities[row.workflowId]?.normalizedIssueKey == normalizedIssueKey
    }
    .filter { row -> identities[row.workflowId] != null || !row.artifactsJson.contains("decomposition_runtime") }
    .filter { row ->
      identities[row.workflowId]?.let { identity ->
        identity.repositoryIdentity == repositoryIdentity && identity.routeScope == FeatureTaskRouteScope.STANDALONE
      } ?: true
    }
    .map { row -> FeatureTaskWorkflowCandidate(identities[row.workflowId], row) }

  override fun findGoalChildFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate> = featureTaskRowsInInsertionOrder()
    .filter { row ->
      identities[row.workflowId]?.let { identity ->
        identity.normalizedIssueKey == normalizedIssueKey &&
          identity.repositoryIdentity == repositoryIdentity &&
          identity.routeScope == FeatureTaskRouteScope.GOAL_CHILD
      } ?: false
    }
    .map { row -> FeatureTaskWorkflowCandidate(identities[row.workflowId], row) }

  override fun countGoalChildIdentities(normalizedIssueKey: String): Int = identities.values.count { identity ->
    identity.normalizedIssueKey == normalizedIssueKey && identity.routeScope == FeatureTaskRouteScope.GOAL_CHILD
  }

  override fun claimFeatureTaskContinuation(workflowId: String, expectedUpdatedAt: String?): Boolean {
    val rows = if (workflowId in implement) implement else taskRuntime
    val existing = rows[workflowId] ?: return false
    if (existing.updatedAt != expectedUpdatedAt ||
      existing.workflowStatus in setOf("running", "completed", "failed", "abandoned")
    ) {
      return false
    }
    rows[workflowId] = existing.copy(workflowStatus = "running", updatedAt = "claimed")
    return true
  }

  override fun saveFeatureImplementWorkflow(row: WorkflowStateRecord) {
    implement[row.workflowId] = row.copy(issueKey = row.issueKey ?: implement[row.workflowId]?.issueKey)
  }
  override fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord) {
    verify[row.workflowId] = row
  }
  override fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord? = implement[workflowId]
  override fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord? = verify[workflowId]
  override fun listFeatureImplementWorkflows(limit: Int): List<WorkflowStateRecord> =
    implement.values.filter { it.mode == null || it.mode == FeatureTaskWorkflowMode.PROSE }.take(limit)
  override fun listFeatureVerifyWorkflows(limit: Int): List<WorkflowStateRecord> = verify.values.toList().take(limit)
  override fun latestFeatureImplementWorkflow(): WorkflowStateRecord? =
    listFeatureImplementWorkflows(Int.MAX_VALUE).lastOrNull()
  override fun latestFeatureVerifyWorkflow(): WorkflowStateRecord? = verify.values.lastOrNull()
  override fun getFeatureImplementSessionSummary(sessionId: String): FeatureImplementSessionSummary? = null
  override fun getFeatureVerifySessionSummary(sessionId: String): FeatureVerifySessionSummary? = null

  override fun terminalizeLegacyProseFeatureTaskWorkflow(row: WorkflowStateRecord) {
    val existing = getFeatureTaskWorkflow(row.workflowId)
      ?: error("Legacy prose feature-task workflow '${row.workflowId}' was not terminalized (missing row).")
    val effectiveMode = existing.mode ?: FeatureTaskWorkflowMode.PROSE
    require(effectiveMode == FeatureTaskWorkflowMode.PROSE) {
      "Legacy prose feature-task workflow '${row.workflowId}' was not terminalized (mode is not prose)."
    }
    val preserved = row.copy(
      mode = existing.mode,
      implementationSkill = existing.implementationSkill,
      issueKey = row.issueKey ?: existing.issueKey,
    )
    when {
      preserved.workflowId in implement -> implement[preserved.workflowId] = preserved
      preserved.workflowId in taskRuntime -> taskRuntime[preserved.workflowId] = preserved
      else -> implement[preserved.workflowId] = preserved
    }
  }

  override fun getFeatureTaskWorkflow(workflowId: String): WorkflowStateRecord? =
    taskRuntime[workflowId] ?: implement[workflowId]
  override fun getFeatureTaskWorkflowAsMode(workflowId: String, mode: FeatureTaskWorkflowMode): WorkflowStateRecord? {
    val row = getFeatureTaskWorkflow(workflowId) ?: return null
    val effectiveMode = row.mode ?: FeatureTaskWorkflowMode.PROSE
    if (effectiveMode != mode) {
      throw InvalidWorkflowStateSchemaError(
        "Feature-task workflow '$workflowId' is mode='${effectiveMode.wireValue}', not '${mode.wireValue}'.",
      )
    }
    return row
  }
  override fun saveFeatureTaskRuntimeWorkflow(row: WorkflowStateRecord) {
    if (failSaveWhen?.invoke(row) == true) {
      error("simulated process kill during the feature-task-runtime save")
    }
    taskRuntime[row.workflowId] = row.copy(issueKey = row.issueKey ?: taskRuntime[row.workflowId]?.issueKey)
  }
  override fun getFeatureTaskRuntimeWorkflow(workflowId: String): WorkflowStateRecord? =
    taskRuntime[workflowId] ?: implement[workflowId]?.takeIf { it.mode == FeatureTaskWorkflowMode.RUNTIME }
  override fun listFeatureTaskRuntimeWorkflows(limit: Int): List<WorkflowStateRecord> =
    (taskRuntime.values + implement.values.filter { it.mode == FeatureTaskWorkflowMode.RUNTIME })
      .distinctBy(WorkflowStateRecord::workflowId)
      .take(limit)
  override fun latestFeatureTaskRuntimeWorkflow(): WorkflowStateRecord? =
    listFeatureTaskRuntimeWorkflows(Int.MAX_VALUE).lastOrNull()

  private val workerOwnershipById = mutableMapOf<String, FeatureTaskRuntimeWorkerOwnership>()

  fun seedWorkerOwnership(ownership: FeatureTaskRuntimeWorkerOwnership) {
    workerOwnershipById[ownership.workflowId] = ownership
  }

  override fun getFeatureTaskRuntimeWorkerOwnership(workflowId: String): FeatureTaskRuntimeWorkerOwnership? =
    workerOwnershipById[workflowId]

  override fun reconcileFeatureTaskRuntimeCrashedWorker(
    workflowId: String,
    ownerToken: String,
    generation: Long,
    interruptionReason: String,
    nowInstant: String,
  ): Boolean {
    val current = workerOwnershipById[workflowId] ?: return false
    val row = taskRuntime[workflowId]
    val leaseStillExpired = runCatching {
      Instant.parse(current.expiresAt).isBefore(Instant.parse(nowInstant))
    }.getOrDefault(false)
    val eligible = current.ownerToken == ownerToken &&
      current.generation == generation &&
      leaseStillExpired &&
      row != null &&
      row.workflowStatus == "running"
    if (!eligible) return false
    workerOwnershipById.remove(workflowId)
    taskRuntime[workflowId] = row.copy(workflowStatus = "pending")
    return true
  }
}

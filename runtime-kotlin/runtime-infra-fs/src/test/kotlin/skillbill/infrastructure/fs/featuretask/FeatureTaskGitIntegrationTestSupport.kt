package skillbill.infrastructure.fs.featuretask

import skillbill.contracts.JsonCodec
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.learning.LearningRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.review.ReviewRepository
import skillbill.ports.telemetry.LifecycleTelemetryRepository
import skillbill.ports.telemetry.TelemetryOutboxRepository
import skillbill.ports.telemetry.TelemetryReconciliationRepository
import skillbill.ports.work.WorkListRepository
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.FeatureImplementSessionSummary
import skillbill.ports.workflow.model.FeatureTaskExecutionIdentity
import skillbill.ports.workflow.model.FeatureTaskWorkflowCandidate
import skillbill.ports.workflow.model.FeatureVerifySessionSummary
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.WorkflowSnapshotValidator
import java.lang.Boolean.TYPE
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.lang.Double.TYPE as DoubleTYPE
import java.lang.Long.TYPE as LongTYPE

internal val featureTaskGitIntegrationSnapshotValidator: WorkflowSnapshotValidator =
  object : WorkflowSnapshotValidator {
    override fun validate(snapshot: Map<String, Any?>, slug: String) = Unit
  }

internal class FeatureTaskGitIntegrationWorkflowRepository : WorkflowStateRepository {
  private val taskRuntimeRows = linkedMapOf<String, WorkflowStateRecord>()

  fun taskRuntimeArtifacts(workflowId: String): Map<String, Any?> {
    val record = requireNotNull(taskRuntimeRows[workflowId]) { "no runtime row for $workflowId" }
    return JsonCodec.parseObjectOrNull(record.artifactsJson)
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap)
      .orEmpty()
  }

  override fun saveFeatureTaskExecutionIdentity(identity: FeatureTaskExecutionIdentity) = Unit

  override fun findStandaloneFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate> = emptyList()

  override fun saveFeatureImplementWorkflow(row: WorkflowStateRecord) = Unit

  override fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord? = null

  override fun listFeatureImplementWorkflows(limit: Int): List<WorkflowStateRecord> = emptyList()

  override fun latestFeatureImplementWorkflow(): WorkflowStateRecord? = null

  override fun getFeatureImplementSessionSummary(sessionId: String): FeatureImplementSessionSummary? = null

  override fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord) = Unit

  override fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord? = null

  override fun listFeatureVerifyWorkflows(limit: Int): List<WorkflowStateRecord> = emptyList()

  override fun latestFeatureVerifyWorkflow(): WorkflowStateRecord? = null

  override fun getFeatureVerifySessionSummary(sessionId: String): FeatureVerifySessionSummary? = null

  override fun saveFeatureTaskRuntimeWorkflow(row: WorkflowStateRecord) {
    taskRuntimeRows[row.workflowId] = row
  }

  override fun getFeatureTaskRuntimeWorkflow(workflowId: String): WorkflowStateRecord? = taskRuntimeRows[workflowId]

  override fun listFeatureTaskRuntimeWorkflows(limit: Int): List<WorkflowStateRecord> =
    taskRuntimeRows.values.toList().asReversed().take(limit)

  override fun latestFeatureTaskRuntimeWorkflow(): WorkflowStateRecord? =
    listFeatureTaskRuntimeWorkflows(1).firstOrNull()

  override fun getFeatureTaskRuntimeWorkerOwnership(workflowId: String) = null
}

internal class FeatureTaskGitIntegrationDatabase(
  private val repository: FeatureTaskGitIntegrationWorkflowRepository,
) : DatabaseSessionFactory {
  private val dbPath = Path.of("/fake/metrics.db")

  override fun resolveDbPath(dbOverride: String?): Path = dbPath

  override fun databaseExists(dbOverride: String?): Boolean = true

  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> selfManagedWrite(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())

  private fun unitOfWork(): UnitOfWork = object : UnitOfWork {
    override val dbPath: Path = this@FeatureTaskGitIntegrationDatabase.dbPath
    override val reviews: ReviewRepository = noopPort(ReviewRepository::class.java)
    override val learnings: LearningRepository = noopPort(LearningRepository::class.java)
    override val lifecycleTelemetry: LifecycleTelemetryRepository =
      noopPort(LifecycleTelemetryRepository::class.java)
    override val telemetryReconciliation: TelemetryReconciliationRepository =
      noopPort(TelemetryReconciliationRepository::class.java)
    override val telemetryOutbox: TelemetryOutboxRepository = noopPort(TelemetryOutboxRepository::class.java)
    override val workflowStates: WorkflowStateRepository = repository
    override val workList: WorkListRepository = noopPort(WorkListRepository::class.java)
    override val goalPlanningPreparations: GoalPlanningPreparationRepository =
      noopPort(GoalPlanningPreparationRepository::class.java)
    override val goalRunnerControls: GoalRunnerControlRepository = EmptyGoalRunnerControlRepository
  }
}

private fun <T> noopPort(type: Class<T>): T = type.cast(
  Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
    defaultPortReturn(method)
  },
)

private fun defaultPortReturn(method: Method): Any? = when {
  method.returnType == Void.TYPE -> null
  List::class.java.isAssignableFrom(method.returnType) -> emptyList<Any>()
  Map::class.java.isAssignableFrom(method.returnType) -> emptyMap<Any, Any>()
  method.returnType == TYPE -> false
  method.returnType == Integer.TYPE -> 0
  method.returnType == LongTYPE -> 0L
  method.returnType == DoubleTYPE -> 0.0
  else -> null
}

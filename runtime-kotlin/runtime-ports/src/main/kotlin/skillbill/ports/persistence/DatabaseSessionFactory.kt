package skillbill.ports.persistence

import java.nio.file.Path

interface DatabaseSessionFactory {
  fun resolveDbPath(dbOverride: String? = null): Path

  fun databaseExists(dbOverride: String? = null): Boolean

  fun <T> read(dbOverride: String? = null, block: (UnitOfWork) -> T): T

  fun <T> transaction(dbOverride: String? = null, block: (UnitOfWork) -> T): T

  /**
   * A write-capable session with no outer transaction, for repository methods that own their own
   * transaction boundary and so cannot be nested inside [transaction]. Distinct from [read], which
   * hands out a connection without write capability. No default: an implementation that inherited
   * [read] here would silently hand a read-only session to a write seam.
   */
  fun <T> selfManagedWrite(dbOverride: String? = null, block: (UnitOfWork) -> T): T
}

interface UnitOfWork {
  val dbPath: Path
  val reviews: ReviewRepository
  val learnings: LearningRepository
  val lifecycleTelemetry: LifecycleTelemetryRepository
  val telemetryReconciliation: TelemetryReconciliationRepository
  val telemetryOutbox: TelemetryOutboxRepository
  val workflowStates: WorkflowStateRepository
  val workList: WorkListRepository
  val goalPlanningPreparations: GoalPlanningPreparationRepository
  val goalRunnerControls: GoalRunnerControlRepository
    get() = EmptyGoalRunnerControlRepository
  val unaddressedFindings: UnaddressedFindingsRepository
    get() = UnavailableUnaddressedFindingsRepository
  val featureTaskRuntimeAuditGenerations: FeatureTaskRuntimeAuditGenerationRepository
    get() = UnavailableFeatureTaskRuntimeAuditGenerationRepository
  val rejectedOutputDiagnostics: RejectedOutputDiagnosticRepository?
    get() = null
  val rejectedOutputDiagnosticPermissions: RejectedOutputDiagnosticPermissions?
    get() = null
}

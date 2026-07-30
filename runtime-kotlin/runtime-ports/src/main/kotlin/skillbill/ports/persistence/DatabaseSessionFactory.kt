package skillbill.ports.persistence

import java.nio.file.Path

interface DatabaseSessionFactory {
  fun resolveDbPath(dbOverride: String? = null): Path

  fun databaseExists(dbOverride: String? = null): Boolean

  fun <T> read(dbOverride: String? = null, block: (UnitOfWork) -> T): T

  fun <T> transaction(dbOverride: String? = null, block: (UnitOfWork) -> T): T
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
  val convergenceStates: ConvergenceStateRepository
    get() = UnavailableConvergenceStateRepository
  val auditGenerations: AuditGenerationStore
    get() = UnavailableAuditGenerationStore
  val auditRepairBatches: AuditRepairBatchStore
    get() = UnavailableAuditRepairBatchStore
  val auditRepairs: AuditRepairQuery
    get() = UnavailableAuditRepairQuery
  val unaddressedFindings: UnaddressedFindingsRepository
    get() = UnavailableUnaddressedFindingsRepository
  val reviewGenerations: ReviewGenerationRepository
    get() = UnavailableReviewGenerationRepository
  val rejectedOutputDiagnostics: RejectedOutputDiagnosticRepository?
    get() = null
  val rejectedOutputDiagnosticPermissions: RejectedOutputDiagnosticPermissions?
    get() = null
}

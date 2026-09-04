package skillbill.ports.persistence

import skillbill.ports.diagnostics.RejectedOutputDiagnosticPermissions
import skillbill.ports.diagnostics.RejectedOutputDiagnosticRepository
import skillbill.ports.featuretask.FeatureTaskRuntimeAuditGenerationRepository
import skillbill.ports.featuretask.UnavailableFeatureTaskRuntimeAuditGenerationRepository
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.ports.goalrunner.UnaddressedFindingsRepository
import skillbill.ports.goalrunner.UnavailableUnaddressedFindingsRepository
import skillbill.ports.idestatus.AgentActivityStampRepository
import skillbill.ports.idestatus.EmptyAgentActivityStampRepository
import skillbill.ports.learning.LearningRepository
import skillbill.ports.review.ReviewRepository
import skillbill.ports.telemetry.LifecycleTelemetryRepository
import skillbill.ports.telemetry.TelemetryOutboxRepository
import skillbill.ports.telemetry.TelemetryReconciliationRepository
import skillbill.ports.work.WorkListRepository
import skillbill.ports.workflow.WorkflowStateRepository
import java.nio.file.Path

interface UnitOfWork : GoalRunnerPersistenceSession {
  val dbPath: Path
  override val reviews: ReviewRepository
  val learnings: LearningRepository
  val lifecycleTelemetry: LifecycleTelemetryRepository
  val telemetryReconciliation: TelemetryReconciliationRepository
  val telemetryOutbox: TelemetryOutboxRepository
  override val workflowStates: WorkflowStateRepository
  val workList: WorkListRepository
  override val goalPlanningPreparations: GoalPlanningPreparationRepository
  override val goalRunnerControls: GoalRunnerControlRepository
  val unaddressedFindings: UnaddressedFindingsRepository
    get() = UnavailableUnaddressedFindingsRepository
  val featureTaskRuntimeAuditGenerations: FeatureTaskRuntimeAuditGenerationRepository
    get() = UnavailableFeatureTaskRuntimeAuditGenerationRepository
  val agentActivityStamps: AgentActivityStampRepository
    get() = EmptyAgentActivityStampRepository
  val rejectedOutputDiagnostics: RejectedOutputDiagnosticRepository?
    get() = null
  val rejectedOutputDiagnosticPermissions: RejectedOutputDiagnosticPermissions?
    get() = null
}

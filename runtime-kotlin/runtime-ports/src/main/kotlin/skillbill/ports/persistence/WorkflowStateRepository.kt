package skillbill.ports.persistence

import skillbill.ports.persistence.model.FeatureImplementSessionSummary
import skillbill.ports.persistence.model.FeatureVerifySessionSummary
import skillbill.ports.persistence.model.WorkflowStateRecord

/**
 * Durable workflow-state persistence, split into one capability interface per
 * family so no single interface crosses the detekt `TooManyFunctions`
 * threshold. This remains the single port adapters implement.
 */
interface WorkflowStateRepository :
  FeatureImplementWorkflowStateRepository,
  FeatureVerifyWorkflowStateRepository,
  FeatureTaskRuntimeWorkflowStateRepository

interface FeatureImplementWorkflowStateRepository {
  fun saveFeatureImplementWorkflow(row: WorkflowStateRecord)

  fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord?

  fun listFeatureImplementWorkflows(limit: Int = 20): List<WorkflowStateRecord>

  fun latestFeatureImplementWorkflow(): WorkflowStateRecord?

  fun getFeatureImplementSessionSummary(sessionId: String): FeatureImplementSessionSummary?
}

interface FeatureVerifyWorkflowStateRepository {
  fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord)

  fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord?

  fun listFeatureVerifyWorkflows(limit: Int = 20): List<WorkflowStateRecord>

  fun latestFeatureVerifyWorkflow(): WorkflowStateRecord?

  fun getFeatureVerifySessionSummary(sessionId: String): FeatureVerifySessionSummary?
}

/**
 * Persistence for the experimental feature-task-runtime pipeline. Per-phase
 * records and the append-only phase ledger ride inside the [WorkflowStateRecord]
 * artifacts envelope; there is intentionally no session-summary method.
 */
interface FeatureTaskRuntimeWorkflowStateRepository {
  fun saveFeatureTaskRuntimeWorkflow(row: WorkflowStateRecord)

  fun getFeatureTaskRuntimeWorkflow(workflowId: String): WorkflowStateRecord?

  fun listFeatureTaskRuntimeWorkflows(limit: Int = 20): List<WorkflowStateRecord>

  fun latestFeatureTaskRuntimeWorkflow(): WorkflowStateRecord?
}

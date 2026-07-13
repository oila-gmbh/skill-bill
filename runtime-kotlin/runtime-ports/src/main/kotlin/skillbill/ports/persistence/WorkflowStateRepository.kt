package skillbill.ports.persistence

import skillbill.ports.persistence.model.FeatureImplementSessionSummary
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.persistence.model.FeatureTaskExecutionIdentity
import skillbill.ports.persistence.model.FeatureTaskWorkflowCandidate
import skillbill.ports.persistence.model.FeatureVerifySessionSummary
import skillbill.ports.persistence.model.WorkflowStateRecord

/**
 * Durable workflow-state persistence, split into one capability interface per
 * family so no single interface crosses the detekt `TooManyFunctions`
 * threshold. This remains the single port adapters implement.
 */
interface WorkflowStateRepository :
  FeatureTaskWorkflowStateRepository,
  FeatureImplementWorkflowStateRepository,
  FeatureVerifyWorkflowStateRepository,
  FeatureTaskRuntimeWorkflowStateRepository

interface FeatureTaskWorkflowStateRepository {
  fun saveFeatureTaskExecutionIdentity(identity: FeatureTaskExecutionIdentity) = Unit

  fun findStandaloneFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate> = emptyList()

  fun saveFeatureTaskWorkflow(row: WorkflowStateRecord, mode: FeatureTaskWorkflowMode) {
    when (mode) {
      FeatureTaskWorkflowMode.PROSE ->
        (this as FeatureImplementWorkflowStateRepository).saveFeatureImplementWorkflow(row)
      FeatureTaskWorkflowMode.RUNTIME ->
        (this as FeatureTaskRuntimeWorkflowStateRepository).saveFeatureTaskRuntimeWorkflow(row)
    }
  }

  fun getFeatureTaskWorkflow(workflowId: String): WorkflowStateRecord? =
    (this as FeatureImplementWorkflowStateRepository).getFeatureImplementWorkflow(workflowId)
      ?: (this as FeatureTaskRuntimeWorkflowStateRepository).getFeatureTaskRuntimeWorkflow(workflowId)

  fun getFeatureTaskWorkflowAsMode(workflowId: String, mode: FeatureTaskWorkflowMode): WorkflowStateRecord? =
    when (mode) {
      FeatureTaskWorkflowMode.PROSE -> (this as FeatureImplementWorkflowStateRepository).getFeatureImplementWorkflow(
        workflowId,
      )
      FeatureTaskWorkflowMode.RUNTIME ->
        (this as FeatureTaskRuntimeWorkflowStateRepository).getFeatureTaskRuntimeWorkflow(workflowId)
    }

  fun listFeatureTaskWorkflows(mode: FeatureTaskWorkflowMode, limit: Int = 20): List<WorkflowStateRecord> =
    when (mode) {
      FeatureTaskWorkflowMode.PROSE -> (this as FeatureImplementWorkflowStateRepository).listFeatureImplementWorkflows(
        limit,
      )
      FeatureTaskWorkflowMode.RUNTIME ->
        (this as FeatureTaskRuntimeWorkflowStateRepository).listFeatureTaskRuntimeWorkflows(limit)
    }

  fun latestFeatureTaskWorkflow(mode: FeatureTaskWorkflowMode): WorkflowStateRecord? = when (mode) {
    FeatureTaskWorkflowMode.PROSE ->
      (this as FeatureImplementWorkflowStateRepository).latestFeatureImplementWorkflow()
    FeatureTaskWorkflowMode.RUNTIME ->
      (this as FeatureTaskRuntimeWorkflowStateRepository).latestFeatureTaskRuntimeWorkflow()
  }
}

interface FeatureImplementWorkflowStateRepository {
  /**
   * Compatibility alias for bill-feature-task mode=prose. Authoritative
   * implementations should store the row in the shared feature-task workflow store.
   */
  fun saveFeatureImplementWorkflow(row: WorkflowStateRecord)

  fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord?

  fun getFeatureImplementWorkflows(workflowIds: Set<String>): Map<String, WorkflowStateRecord> =
    workflowIds.mapNotNull { workflowId -> getFeatureImplementWorkflow(workflowId)?.let { workflowId to it } }.toMap()

  fun listFeatureImplementWorkflows(limit: Int = 20): List<WorkflowStateRecord>

  fun latestFeatureImplementWorkflow(): WorkflowStateRecord?

  fun getFeatureImplementSessionSummary(sessionId: String): FeatureImplementSessionSummary?
}

interface FeatureVerifyWorkflowStateRepository {
  fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord)

  fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord?

  fun getFeatureVerifyWorkflows(workflowIds: Set<String>): Map<String, WorkflowStateRecord> =
    workflowIds.mapNotNull { workflowId -> getFeatureVerifyWorkflow(workflowId)?.let { workflowId to it } }.toMap()

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
  /**
   * Compatibility alias for bill-feature-task mode=runtime. Authoritative
   * implementations should store the row in the shared feature-task workflow store.
   */
  fun saveFeatureTaskRuntimeWorkflow(row: WorkflowStateRecord)

  fun getFeatureTaskRuntimeWorkflow(workflowId: String): WorkflowStateRecord?

  fun getFeatureTaskRuntimeWorkflows(workflowIds: Set<String>): Map<String, WorkflowStateRecord> =
    workflowIds.mapNotNull { workflowId -> getFeatureTaskRuntimeWorkflow(workflowId)?.let { workflowId to it } }.toMap()

  fun listFeatureTaskRuntimeWorkflows(limit: Int = 20): List<WorkflowStateRecord>

  fun latestFeatureTaskRuntimeWorkflow(): WorkflowStateRecord?
}

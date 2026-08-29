package skillbill.db.workflow

import skillbill.ports.workflow.FeatureImplementWorkflowStateRepository
import skillbill.ports.workflow.FeatureTaskRuntimeWorkerRepository
import skillbill.ports.workflow.FeatureTaskRuntimeWorkflowStateRepository
import skillbill.ports.workflow.FeatureTaskWorkflowStateRepository
import skillbill.ports.workflow.FeatureVerifyWorkflowStateRepository
import skillbill.ports.workflow.GoalChildWorkflowStateRepository
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowStateRecord
import java.sql.Connection

typealias WorkflowStateRow = WorkflowStateRecord

internal const val WORKFLOW_ID_PARAMETER_INDEX: Int = 1
internal const val IDENTITY_WORKFLOW_ID_INDEX: Int = 1
internal const val IDENTITY_CONTRACT_VERSION_INDEX: Int = 2
internal const val IDENTITY_ISSUE_KEY_INDEX: Int = 3
internal const val IDENTITY_REPOSITORY_INDEX: Int = 4
internal const val IDENTITY_SPEC_PATH_INDEX: Int = 5
internal const val IDENTITY_MODE_INDEX: Int = 6
internal const val IDENTITY_ROUTE_SCOPE_INDEX: Int = 7
internal const val CLAIM_WORKFLOW_ID_INDEX: Int = 1
internal const val CLAIM_EXPECTED_UPDATED_AT_NULL_INDEX: Int = 2
internal const val CLAIM_EXPECTED_UPDATED_AT_INDEX: Int = 3
internal const val LOOKUP_WORKFLOW_ISSUE_KEY_INDEX: Int = 1
internal const val LOOKUP_IDENTITY_ISSUE_KEY_INDEX: Int = 2
internal const val LOOKUP_LEGACY_ROUTE_SCOPE_INDEX: Int = 3
internal const val LOOKUP_REPOSITORY_IDENTITY_INDEX: Int = 4
internal const val LOOKUP_ROUTE_SCOPE_INDEX: Int = 5
internal const val DELETE_GOAL_CHILD_FIRST_STATUS_INDEX: Int = 2
internal const val MINIMUM_OWNER_TOKEN_LENGTH: Int = 16

class WorkflowStateStore private constructor(
  connection: Connection,
  featureTaskStore: FeatureTaskWorkflowStateStore,
) : WorkflowStateRepository,
  FeatureTaskWorkflowStateRepository by featureTaskStore,
  GoalChildWorkflowStateRepository by featureTaskStore,
  FeatureTaskRuntimeWorkerRepository by featureTaskStore,
  FeatureImplementWorkflowStateRepository by FeatureImplementWorkflowStateStore(connection),
  FeatureVerifyWorkflowStateRepository by FeatureVerifyWorkflowStateStore(connection),
  FeatureTaskRuntimeWorkflowStateRepository by FeatureTaskRuntimeWorkflowStateStore(connection) {
  constructor(connection: Connection) : this(connection, FeatureTaskWorkflowStateStore(connection))
}

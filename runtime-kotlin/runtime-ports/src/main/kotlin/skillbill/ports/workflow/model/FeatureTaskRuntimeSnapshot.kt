package skillbill.ports.workflow.model

data class FeatureTaskRuntimeSnapshot(
  val workflow: WorkflowStateRecord,
  val identity: FeatureTaskExecutionIdentity? = null,
)

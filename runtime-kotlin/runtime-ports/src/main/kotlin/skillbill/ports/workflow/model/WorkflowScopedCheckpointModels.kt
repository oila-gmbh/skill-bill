package skillbill.ports.workflow.model

import skillbill.workflow.taskruntime.model.WorkflowCheckpointIdentity

data class WorkflowScopedCheckpointRequest(
  val branch: String,
  val phase: String,
  val loop: String,
  val generation: Int,
  val ownedPaths: List<String>,
  val observedPaths: List<String> = ownedPaths,
  val expectedContentIdentities: Map<String, String> = emptyMap(),
  val governedSpecRoot: String? = null,
  val commitMessage: String,
)

data class WorkflowScopedCheckpointResult(
  val status: String,
  val identity: WorkflowCheckpointIdentity? = null,
  val policyBlock: WorkflowCheckpointPolicyBlock? = null,
  val error: String = "",
) {
  val ok: Boolean get() = status == "ok"
}

data class WorkflowCheckpointPolicyBlock(
  val code: String,
  val path: String,
  val recoveryGuidance: String,
  val retryable: Boolean = false,
)

package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import java.math.BigDecimal
import java.math.BigInteger

data class FeatureTaskRuntimeOperatorBlockRetry(
  val phaseId: String,
  val reason: String,
  val retriedAt: String,
) {
  init {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimeOperatorBlockRetry.phaseId must be non-blank." }
    require(reason.length in 1..FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_REASON_MAX_LENGTH && reason.isNotBlank()) {
      "FeatureTaskRuntimeOperatorBlockRetry.reason must contain " +
        "1..$FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_REASON_MAX_LENGTH characters."
    }
    require(retriedAt.isNotBlank()) { "FeatureTaskRuntimeOperatorBlockRetry.retriedAt must be non-blank." }
  }
}

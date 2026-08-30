@file:Suppress("TooGenericExceptionCaught")

package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation

internal object StructuralRepairDecisions {
  fun accepted(
    text: String,
    node: JsonNode,
    evidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision =
    FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Accepted(text, node, evidence)

  fun reject(
    code: FeatureTaskRuntimePhaseOutputFailureCode,
    reason: String,
    sourceLocation: FeatureTaskRuntimePhaseOutputSourceLocation? = null,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision =
    FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Rejected(code, reason, sourceLocation)
}

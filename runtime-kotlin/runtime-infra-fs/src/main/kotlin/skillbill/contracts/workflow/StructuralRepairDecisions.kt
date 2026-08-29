@file:Suppress("TooGenericExceptionCaught", "LongMethod")

package skillbill.contracts.workflow

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation
import java.security.MessageDigest

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


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

/** Internal parse result; Jackson nodes never cross the domain port. */
internal sealed interface FeatureTaskRuntimePhaseOutputStructuralRepairDecision {
  data class Accepted(
    val text: String,
    val node: JsonNode,
    val evidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  ) : FeatureTaskRuntimePhaseOutputStructuralRepairDecision

  data class Rejected(
    val code: FeatureTaskRuntimePhaseOutputFailureCode,
    val reason: String,
    val sourceLocation: FeatureTaskRuntimePhaseOutputSourceLocation? = null,
  ) : FeatureTaskRuntimePhaseOutputStructuralRepairDecision
}

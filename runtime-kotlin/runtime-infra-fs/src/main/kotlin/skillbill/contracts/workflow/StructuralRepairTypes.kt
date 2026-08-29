@file:Suppress("TooGenericExceptionCaught", "LongMethod")

package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence

internal data class CandidateGeneration(
  val candidates: List<Candidate>,
  val limitExceeded: Boolean,
  val unsupportedYaml: Boolean,
)

internal data class Candidate(
  val text: String,
  val format: FeatureTaskRuntimePhaseOutputFormat,
  val changedOffset: Int,
)

internal data class DelimiterScan(
  val openingStack: List<Char>,
  val unmatchedClosingOffsets: List<Int>,
  val firstMismatchedClosing: MismatchedClosing?,
)

internal data class MismatchedClosing(
  val offset: Int,
  val missingCloser: Char?,
)

internal data class TextCandidate(
  val text: String,
  val sourceOffset: Int,
  val sourceEnd: Int,
)

internal data class EmbeddedDocument(
  val text: String,
  val node: JsonNode,
  val evidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  val sourceStart: Int,
  val sourceEnd: Int,
)

internal sealed interface StrictParse {
  data class Success(
    val format: FeatureTaskRuntimePhaseOutputFormat,
    val node: JsonNode,
  ) : StrictParse

  data class Failure(
    val code: FeatureTaskRuntimePhaseOutputFailureCode,
    val reason: String,
  ) : StrictParse
}

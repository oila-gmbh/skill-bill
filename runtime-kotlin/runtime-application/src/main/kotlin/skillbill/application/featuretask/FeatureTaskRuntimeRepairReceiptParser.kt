package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimeRepairReceiptError
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.featureTaskRuntimeRemediationRoundNumber

internal fun featureTaskRuntimeParseRepairReceiptOrNull(
  producedOutputs: Map<String, Any?>,
  remediationBaseSha: String,
  roundNumber: Int,
): FeatureTaskRuntimeRepairReceipt? {
  val raw = producedOutputs["repair_receipt"] ?: return null
  val map = requireRepairReceiptMap(raw) +
    ("pre_fix_checkpoint_sha" to remediationBaseSha) +
    ("round_number" to roundNumber)
  return try {
    FeatureTaskRuntimeRepairReceipt.fromArtifactMap(map, "repair_receipt")
  } catch (error: InvalidFeatureTaskRuntimeRepairReceiptError) {
    throw error
  } catch (error: InvalidGoalSubtaskReviewStateSchemaError) {
    throw InvalidFeatureTaskRuntimeRepairReceiptError(
      fieldPath = error.fieldPath,
      reason = error.reason,
      payloadFreeReason = error.reason,
      cause = error,
    )
  }
}

private fun requireRepairReceiptMap(raw: Any): Map<String, Any?> = JsonSupport.anyToStringAnyMap(raw)
  ?: throw InvalidFeatureTaskRuntimeRepairReceiptError(
    fieldPath = "repair_receipt",
    reason = "must be an object.",
    payloadFreeReason = "repair_receipt must be an object.",
  )

internal sealed interface FeatureTaskRuntimeRepairReceiptParse

internal data object FeatureTaskRuntimeRepairReceiptMissing : FeatureTaskRuntimeRepairReceiptParse

internal data class FeatureTaskRuntimeRepairReceiptValid(
  val receipt: FeatureTaskRuntimeRepairReceipt,
) : FeatureTaskRuntimeRepairReceiptParse

internal data class FeatureTaskRuntimeRepairReceiptRejected(
  val fieldPath: String,
  val payloadFreeReason: String,
) : FeatureTaskRuntimeRepairReceiptParse {
  val rejectionDetail: String get() = featureTaskRuntimeRepairReceiptRejectionDetail(fieldPath, payloadFreeReason)
}

private val REPAIR_RECEIPT_POINTER_INDEX = Regex("""\[(\d+)]""")

internal fun featureTaskRuntimeRepairReceiptRejectionDetail(fieldPath: String, payloadFreeReason: String): String {
  val relative = fieldPath.removePrefix("repair_receipt").trim('.')
  val pointer = (if (relative.isEmpty()) "repair_receipt" else "repair_receipt.$relative")
    .replace(REPAIR_RECEIPT_POINTER_INDEX) { match -> ".${match.groupValues[1]}" }
    .split('.')
    .filter(String::isNotBlank)
    .joinToString("/", prefix = "/")
  return "[repair-receipt] $pointer: $payloadFreeReason"
}

internal fun featureTaskRuntimeParseRepairReceipt(
  producedOutputs: Map<String, Any?>,
  remediationBaseSha: String,
  roundNumber: Int,
): FeatureTaskRuntimeRepairReceiptParse = try {
  featureTaskRuntimeParseRepairReceiptOrNull(producedOutputs, remediationBaseSha, roundNumber)
    ?.let(::FeatureTaskRuntimeRepairReceiptValid)
    ?: FeatureTaskRuntimeRepairReceiptMissing
} catch (error: InvalidFeatureTaskRuntimeRepairReceiptError) {
  FeatureTaskRuntimeRepairReceiptRejected(error.fieldPath, error.payloadFreeReason)
}

internal fun featureTaskRuntimeRemediationRoundNumberOrNull(reviewState: GoalSubtaskReviewState): Int? =
  runCatching { featureTaskRuntimeRemediationRoundNumber(reviewState.completedPassCount) }
    .getOrElse { error ->
      if (error is InvalidFeatureTaskRuntimeRepairReceiptError) null else throw error
    }

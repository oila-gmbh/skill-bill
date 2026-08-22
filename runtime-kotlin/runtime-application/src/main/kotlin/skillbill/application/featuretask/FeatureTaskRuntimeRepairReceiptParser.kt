package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimeRepairReceiptError
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairConstruct
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairDisturbedRemedy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedger
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.featureTaskRuntimeRemediationRoundNumber
import skillbill.workflow.taskruntime.model.featureTaskRuntimeUndeclaredDisturbances

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

/**
 * The entry-shape gate for a round that has no runtime-owned anchor to stamp. Rejecting on the
 * absent anchor is what produced the unrepairable loop this seam exists to end, but the entries
 * still carry the sanitizer contract, and a receipt is durable either way.
 */
internal fun featureTaskRuntimeRepairReceiptShapeRejection(producedOutputs: Map<String, Any?>): String? {
  val raw = producedOutputs["repair_receipt"] ?: return null
  return try {
    FeatureTaskRuntimeRepairReceipt.validateEntries(requireRepairReceiptMap(raw), "repair_receipt")
    null
  } catch (error: InvalidFeatureTaskRuntimeRepairReceiptError) {
    featureTaskRuntimeRepairReceiptRejectionDetail(error.fieldPath, error.payloadFreeReason)
  } catch (error: InvalidGoalSubtaskReviewStateSchemaError) {
    featureTaskRuntimeRepairReceiptRejectionDetail(error.fieldPath, error.reason)
  }
}

/**
 * Coverage and undeclared disturbances are unfinished repair work on a well-formed receipt, so they
 * must not spend the output-gate budget. Shape defects still reject through
 * [featureTaskRuntimeRepairReceiptShapeRejection].
 *
 * When a round rewrites constructs that hold a settled finding closed but omits
 * `disturbed_remedies`, the runtime stamps the missing declarations. The ledger still reopens those
 * findings for the next review; the producer is not allowed to silently drop them from memory.
 */
internal fun featureTaskRuntimeRepairReceiptWithDeclaredDisturbances(
  receipt: FeatureTaskRuntimeRepairReceipt,
  reviewState: GoalSubtaskReviewState,
): FeatureTaskRuntimeRepairReceipt =
  derivedRepairLedgerOrNull(reviewState)?.let { ledger ->
    featureTaskRuntimeRepairReceiptWithDeclaredDisturbances(receipt, ledger)
  } ?: receipt

internal fun featureTaskRuntimeRepairReceiptWithDeclaredDisturbances(
  receipt: FeatureTaskRuntimeRepairReceipt,
  ledger: FeatureTaskRuntimeRepairLedger,
): FeatureTaskRuntimeRepairReceipt {
  val undeclared = featureTaskRuntimeUndeclaredDisturbances(receipt, ledger)
  if (undeclared.isEmpty()) return receipt
  val additions = undeclared.map { entry ->
    val symbols = entry.constructs.joinToString(", ", transform = FeatureTaskRuntimeRepairConstruct::symbol)
    FeatureTaskRuntimeRepairDisturbedRemedy(
      findingRef = entry.disturbanceRef,
      reason = "Runtime declared: this round rewrote constructs that closed this finding ($symbols).",
    )
  }
  return receipt.copy(disturbedRemedies = receipt.disturbedRemedies + additions)
}

internal fun featureTaskRuntimeRepairReceiptRuntimeDeclaredDisturbanceRefs(
  receipt: FeatureTaskRuntimeRepairReceipt,
  reviewState: GoalSubtaskReviewState,
): List<String> {
  val ledger = derivedRepairLedgerOrNull(reviewState) ?: return emptyList()
  return featureTaskRuntimeUndeclaredDisturbances(receipt, ledger).map { it.disturbanceRef }
}

private fun derivedRepairLedgerOrNull(reviewState: GoalSubtaskReviewState): FeatureTaskRuntimeRepairLedger? =
  runCatching { reviewState.repairLedger }.getOrNull()

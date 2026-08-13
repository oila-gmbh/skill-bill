package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimeRepairReceiptError
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.coversCarriedFindings
import skillbill.workflow.taskruntime.model.featureTaskRuntimeRemediationRoundNumber
import skillbill.workflow.taskruntime.model.stampIdentityFromCompactFindings

private const val MISSING_REMEDIATION_BASE_REASON =
  "pre_fix_checkpoint_sha must match the durable remediation base recorded at this round's pre-fix checkpoint."

/**
 * Untrusted-input seam for `produced_outputs.repair_receipt`. Schema validation has already
 * accepted the envelope; this parse builds the domain model and names payload-free semantic
 * failures (anchor mismatch, omitted carried finding, round mismatch) the settle gate rejects on.
 *
 * A missing key returns null so a test stand-in that skipped the phase-output schema does not
 * invent a receipt. Production completed `implement_fix` output cannot omit the key: the schema
 * allOf block requires it.
 */
internal fun featureTaskRuntimeParseRepairReceiptOrNull(
  producedOutputs: Map<String, Any?>,
): FeatureTaskRuntimeRepairReceipt? {
  val raw = producedOutputs["repair_receipt"] ?: return null
  val map = requireRepairReceiptMap(raw)
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
  val payloadFreeReason: String,
) : FeatureTaskRuntimeRepairReceiptParse

internal fun featureTaskRuntimeParseRepairReceipt(
  producedOutputs: Map<String, Any?>,
): FeatureTaskRuntimeRepairReceiptParse = try {
  featureTaskRuntimeParseRepairReceiptOrNull(producedOutputs)
    ?.let(::FeatureTaskRuntimeRepairReceiptValid)
    ?: FeatureTaskRuntimeRepairReceiptMissing
} catch (error: InvalidFeatureTaskRuntimeRepairReceiptError) {
  FeatureTaskRuntimeRepairReceiptRejected(error.payloadFreeReason)
}

internal fun featureTaskRuntimeRepairReceiptSettleRejection(
  receipt: FeatureTaskRuntimeRepairReceipt,
  reviewState: GoalSubtaskReviewState,
): String? {
  val baseSha = reviewState.remediationBaseSha ?: return MISSING_REMEDIATION_BASE_REASON
  val roundNumber = try {
    featureTaskRuntimeRemediationRoundNumber(reviewState.completedPassCount)
  } catch (error: InvalidFeatureTaskRuntimeRepairReceiptError) {
    return error.payloadFreeReason
  }
  return featureTaskRuntimeRepairReceiptAnchorRejection(receipt, baseSha)
    ?: featureTaskRuntimeRepairReceiptCoverageRejection(
      receipt,
      reviewState.passResults.lastOrNull()?.findings.orEmpty(),
    )
    ?: featureTaskRuntimeRepairReceiptRoundRejection(receipt, roundNumber)
}

internal fun featureTaskRuntimeRepairReceiptAnchorRejection(
  receipt: FeatureTaskRuntimeRepairReceipt,
  remediationBaseSha: String,
): String? = if (receipt.preFixCheckpointSha == remediationBaseSha) {
  null
} else {
  "pre_fix_checkpoint_sha must match the durable remediation base recorded at this round's pre-fix checkpoint."
}

internal fun featureTaskRuntimeRepairReceiptCoverageRejection(
  receipt: FeatureTaskRuntimeRepairReceipt,
  carriedFindings: List<GoalSubtaskReviewCompactFinding>,
): String? = if (receipt.coversCarriedFindings(carriedFindings)) {
  null
} else {
  "repair_receipt.entries must include one entry for every finding carried into this round; " +
    "omitted findings require an explicit no_edit_required outcome."
}

internal fun featureTaskRuntimeRepairReceiptRoundRejection(
  receipt: FeatureTaskRuntimeRepairReceipt,
  durableRoundNumber: Int,
): String? = if (receipt.roundNumber == durableRoundNumber) {
  null
} else {
  "round_number must match the durable remediation round at implement_fix entry."
}

internal fun featureTaskRuntimePreparedRepairReceipt(
  parsed: FeatureTaskRuntimeRepairReceipt,
  roundNumber: Int,
  lastPassFindings: List<GoalSubtaskReviewCompactFinding>,
): FeatureTaskRuntimeRepairReceipt {
  featureTaskRuntimeRepairReceiptRoundRejection(parsed, roundNumber)?.let { reason ->
    throw InvalidFeatureTaskRuntimeRepairReceiptError(
      fieldPath = "round_number",
      reason = reason,
      payloadFreeReason = reason,
    )
  }
  return parsed.stampIdentityFromCompactFindings(lastPassFindings)
}

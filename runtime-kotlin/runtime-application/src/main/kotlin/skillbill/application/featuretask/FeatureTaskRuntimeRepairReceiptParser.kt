package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimeRepairReceiptError
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.coversCarriedFindings
import skillbill.workflow.taskruntime.model.stampIdentityFromCompactFindings

/**
 * Untrusted-input seam for `produced_outputs.repair_receipt`. Schema validation has already
 * accepted the envelope; this parse builds the domain model and names payload-free semantic
 * failures (anchor mismatch, omitted carried finding) the settle gate rejects on.
 *
 * A missing key returns null so a test stand-in that skipped the phase-output schema does not
 * invent a receipt. Production completed `implement_fix` output cannot omit the key: the schema
 * allOf block requires it.
 */
internal fun featureTaskRuntimeParseRepairReceiptOrNull(
  producedOutputs: Map<String, Any?>,
): FeatureTaskRuntimeRepairReceipt? {
  val raw = producedOutputs["repair_receipt"] ?: return null
  val map = JsonSupport.anyToStringAnyMap(raw)
    ?: throw InvalidFeatureTaskRuntimeRepairReceiptError(
      fieldPath = "repair_receipt",
      reason = "must be an object.",
      payloadFreeReason = "repair_receipt must be an object.",
    )
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

internal fun featureTaskRuntimePreparedRepairReceipt(
  parsed: FeatureTaskRuntimeRepairReceipt,
  roundNumber: Int,
  lastPassFindings: List<GoalSubtaskReviewCompactFinding>,
): FeatureTaskRuntimeRepairReceipt =
  parsed.copy(roundNumber = roundNumber).stampIdentityFromCompactFindings(lastPassFindings)


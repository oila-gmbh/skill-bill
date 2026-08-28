package skillbill.workflow.taskruntime.model

import skillbill.text.Utf8Text

class FeatureTaskRuntimeRepairReceiptDecodeObservations {
  val truncationRecords: MutableList<String> = mutableListOf()
}

internal fun forwardOptionalReceiptReason(
  raw: String?,
  fieldPath: String,
  maxUtf8Bytes: Int,
  observations: FeatureTaskRuntimeRepairReceiptDecodeObservations?,
): String? {
  val trimmed = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
  val forwarded = Utf8Text.truncateToUtf8Bytes(trimmed, maxUtf8Bytes)
  if (Utf8Text.utf8Size(forwarded) < Utf8Text.utf8Size(trimmed)) {
    observations?.truncationRecords?.add(repairReceiptReasonTruncationRecord(fieldPath, maxUtf8Bytes))
  }
  return forwarded
}

internal fun repairReceiptReasonTruncationRecord(fieldPath: String, maxUtf8Bytes: Int): String =
  "seam=FeatureTaskRuntimeRepairReceiptEntry.fromArtifactMap " +
    "value_used='$fieldPath truncated to $maxUtf8Bytes UTF-8 bytes' " +
    "value_expected='$fieldPath within $maxUtf8Bytes UTF-8 bytes' " +
    "cause=agent census reason exceeded the repair receipt byte cap"

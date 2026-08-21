package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskRuntimeRepairReceiptError

const val REPAIR_RECEIPT_MAX_ENTRIES: Int = 50
const val REPAIR_RECEIPT_MAX_CONSTRUCTS_PER_ENTRY: Int = 16
const val REPAIR_RECEIPT_MAX_INTENT_UTF8_BYTES: Int = 356
const val REPAIR_RECEIPT_MAX_CONSTRUCT_SYMBOL_UTF8_BYTES: Int = 256
const val REPAIR_RECEIPT_MAX_CONSTRUCT_FILE_UTF8_BYTES: Int = 128
const val REPAIR_RECEIPT_MAX_NO_EDIT_REASON_UTF8_BYTES: Int = 356
const val REPAIR_RECEIPT_MAX_UNRESOLVED_REASON_UTF8_BYTES: Int = 356
const val REPAIR_RECEIPT_MAX_LABEL_UTF8_BYTES: Int = 256
const val REPAIR_RECEIPT_MAX_TEXT_UTF8_BYTES: Int = 256
const val REPAIR_RECEIPT_MAX_DISTURBED_REMEDIES: Int = 50
const val REPAIR_RECEIPT_MAX_DISTURBANCE_REASON_UTF8_BYTES: Int = 356

private val GIT_COMMIT_SHA = Regex("^[0-9a-f]{40}(?:[0-9a-f]{24})?$")

/**
 * Invariant checks in a receipt `init` name their field without knowing where the value sits in the
 * payload, so a bad entry would otherwise report `text` — a key that does not exist under the
 * receipt's `additionalProperties: false`. Re-anchoring the bare name onto the decode path is what
 * makes the reported JSON pointer address the offending entry the producer has to repair.
 */
private fun <T> anchoredToDecodePath(path: String, decode: () -> T): T = try {
  decode()
} catch (error: InvalidFeatureTaskRuntimeRepairReceiptError) {
  if (error.fieldPath.startsWith(path)) {
    throw error
  }
  throw InvalidFeatureTaskRuntimeRepairReceiptError(
    fieldPath = "$path.${error.fieldPath.substringAfterLast('.')}",
    reason = error.reason,
    payloadFreeReason = error.payloadFreeReason,
    cause = error,
  )
}

enum class FeatureTaskRuntimeRepairOutcome(val wireValue: String) {
  ADDRESSED("addressed"),
  NO_EDIT_REQUIRED("no_edit_required"),

  /**
   * The round tried and the finding is still open. Without it a round that could not close a finding
   * has no honest entry to write: `addressed` asserts an edit that closed it and `no_edit_required`
   * asserts no edit was warranted, so the only exit was to omit the finding — which is exactly the
   * silent loss the coverage gate exists to catch. The finding gets one more fix attempt; reported
   * unresolved twice it blocks, carrying the producer's own account of what still fails.
   */
  ATTEMPTED_UNRESOLVED("attempted_unresolved"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeRepairOutcome = entries.firstOrNull { it.wireValue == value }
      ?: receiptError(
        "outcome",
        "must be one of ${entries.joinToString { it.wireValue }}.",
      )
  }
}

/**
 * Normalized construct key (trim, lowercase, whitespace collapse) so the same
 * construct written two ways compares equal. Subtasks 2 through 4 key off this.
 */
@ConsistentCopyVisibility
data class FeatureTaskRuntimeRepairConstructIdentity internal constructor(val key: String) {
  companion object {
    fun of(file: String?, symbol: String): FeatureTaskRuntimeRepairConstructIdentity {
      val normalizedSymbol = normalizeIdentityPart(symbol)
      val normalizedFile = file?.let(::normalizeIdentityPart)?.takeIf(String::isNotEmpty)
      val key = if (normalizedFile == null) normalizedSymbol else "$normalizedFile|$normalizedSymbol"
      return FeatureTaskRuntimeRepairConstructIdentity(key)
    }
  }
}

data class FeatureTaskRuntimeRepairConstruct(
  val symbol: String,
  val file: String? = null,
) {
  val identity: FeatureTaskRuntimeRepairConstructIdentity =
    FeatureTaskRuntimeRepairConstructIdentity.of(file, symbol)

  init {
    requireReceiptSymbol(symbol, "construct.symbol")
    file?.let { basename -> requireReceiptFileBasename(basename, "construct.file") }
  }

  @OpenBoundaryMap("Repair construct at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "symbol" to symbol,
  ).apply { file?.let { put("file", it) } }

  companion object {
    @OpenBoundaryMap("Repair construct decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>, path: String): FeatureTaskRuntimeRepairConstruct {
      raw.requireOnlyReviewStateKeys(setOf("symbol", "file"), path)
      return anchoredToDecodePath(path) {
        FeatureTaskRuntimeRepairConstruct(
          symbol = raw.requireReviewStateString("symbol", path),
          file = raw.optionalReviewStateString("file", path),
        )
      }
    }
  }
}

data class FeatureTaskRuntimeRepairDisturbedRemedy(
  val findingRef: String,
  val reason: String,
) {
  init {
    requireReceiptIdentityText(findingRef, "disturbed_remedies.finding_ref", REPAIR_RECEIPT_MAX_LABEL_UTF8_BYTES)
    requireReceiptSanitizedText(
      reason,
      "disturbed_remedies.reason",
      REPAIR_RECEIPT_MAX_DISTURBANCE_REASON_UTF8_BYTES,
    )
  }

  @OpenBoundaryMap("Disturbed-remedy declaration at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "finding_ref" to findingRef,
    "reason" to reason,
  )

  companion object {
    @OpenBoundaryMap("Disturbed-remedy decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>, path: String): FeatureTaskRuntimeRepairDisturbedRemedy {
      raw.requireOnlyReviewStateKeys(setOf("finding_ref", "reason"), path)
      return anchoredToDecodePath(path) {
        FeatureTaskRuntimeRepairDisturbedRemedy(
          findingRef = raw.requireReviewStateString("finding_ref", path),
          reason = raw.requireReviewStateString("reason", path),
        )
      }
    }
  }
}

data class FeatureTaskRuntimeRepairReceiptEntry(
  val severity: String,
  val label: String,
  val text: String,
  val outcome: FeatureTaskRuntimeRepairOutcome,
  val constructs: List<FeatureTaskRuntimeRepairConstruct>,
  val intent: String,
  val findingId: String? = null,
  val noEditReason: String? = null,
  val unresolvedReason: String? = null,
) {
  init {
    if (severity !in setOf("blocker", "major", "minor", "nit")) {
      receiptError("severity", "must be one of blocker, major, minor, nit.")
    }
    requireReceiptSanitizedText(label, "label", REPAIR_RECEIPT_MAX_LABEL_UTF8_BYTES)
    requireReceiptSanitizedText(text, "text", REPAIR_RECEIPT_MAX_TEXT_UTF8_BYTES)
    findingId?.let { requireReceiptIdentityText(it, "finding_id", REPAIR_RECEIPT_MAX_LABEL_UTF8_BYTES) }
    requireReceiptSanitizedText(intent, "intent", REPAIR_RECEIPT_MAX_INTENT_UTF8_BYTES)
    if (constructs.size > REPAIR_RECEIPT_MAX_CONSTRUCTS_PER_ENTRY) {
      receiptError(
        "constructs",
        "allows at most $REPAIR_RECEIPT_MAX_CONSTRUCTS_PER_ENTRY constructs per entry.",
      )
    }
    when (outcome) {
      FeatureTaskRuntimeRepairOutcome.ADDRESSED -> {
        if (constructs.isEmpty()) {
          receiptError("constructs", "an addressed entry must name at least one closing construct.")
        }
        if (noEditReason != null) {
          receiptError("no_edit_reason", "must be absent when outcome is addressed.")
        }
        if (unresolvedReason != null) {
          receiptError("unresolved_reason", "must be absent when outcome is addressed.")
        }
      }
      FeatureTaskRuntimeRepairOutcome.NO_EDIT_REQUIRED -> {
        val reason = noEditReason
          ?: receiptError("no_edit_reason", "must be present when outcome is no_edit_required.")
        requireReceiptSanitizedText(reason, "no_edit_reason", REPAIR_RECEIPT_MAX_NO_EDIT_REASON_UTF8_BYTES)
        if (unresolvedReason != null) {
          receiptError("unresolved_reason", "must be absent when outcome is no_edit_required.")
        }
      }
      FeatureTaskRuntimeRepairOutcome.ATTEMPTED_UNRESOLVED -> {
        if (constructs.isEmpty()) {
          receiptError("constructs", "an attempted_unresolved entry must name the constructs it touched.")
        }
        if (noEditReason != null) {
          receiptError("no_edit_reason", "must be absent when outcome is attempted_unresolved.")
        }
        val reason = unresolvedReason
          ?: receiptError("unresolved_reason", "must be present when outcome is attempted_unresolved.")
        requireReceiptSanitizedText(reason, "unresolved_reason", REPAIR_RECEIPT_MAX_UNRESOLVED_REASON_UTF8_BYTES)
      }
    }
  }

  fun findingIdentity(): String = compactReviewFindingIdentity(
    GoalSubtaskReviewCompactFinding(severity = severity, label = label, text = text, findingId = findingId),
  )

  @OpenBoundaryMap("Repair receipt entry at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "severity" to severity,
    "label" to label,
    "text" to text,
    "outcome" to outcome.wireValue,
    "constructs" to constructs.map(FeatureTaskRuntimeRepairConstruct::toArtifactMap),
    "intent" to intent,
  ).apply {
    findingId?.let { put("finding_id", it) }
    noEditReason?.let { put("no_edit_reason", it) }
    unresolvedReason?.let { put("unresolved_reason", it) }
  }

  companion object {
    @OpenBoundaryMap("Repair receipt entry decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>, path: String): FeatureTaskRuntimeRepairReceiptEntry {
      raw.requireOnlyReviewStateKeys(
        setOf(
          "severity",
          "label",
          "text",
          "outcome",
          "constructs",
          "intent",
          "finding_id",
          "no_edit_reason",
          "unresolved_reason",
        ),
        path,
      )
      val constructs = raw.requireReviewStateList("constructs", path).mapIndexed { index, value ->
        FeatureTaskRuntimeRepairConstruct.fromArtifactMap(
          value.asReviewStateMap("$path.constructs[$index]"),
          "$path.constructs[$index]",
        )
      }
      return anchoredToDecodePath(path) {
        FeatureTaskRuntimeRepairReceiptEntry(
          severity = raw.requireReviewStateString("severity", path),
          label = raw.requireReviewStateString("label", path),
          text = raw.requireReviewStateString("text", path),
          outcome = FeatureTaskRuntimeRepairOutcome.fromWire(raw.requireReviewStateString("outcome", path)),
          constructs = constructs,
          intent = raw.requireReviewStateString("intent", path),
          findingId = raw.optionalReviewStateString("finding_id", path),
          noEditReason = raw.optionalReviewStateString("no_edit_reason", path),
          unresolvedReason = raw.optionalReviewStateString("unresolved_reason", path),
        )
      }
    }
  }
}

data class FeatureTaskRuntimeRepairReceipt(
  val contractVersion: String = FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION,
  val roundNumber: Int,
  val preFixCheckpointSha: String,
  val entries: List<FeatureTaskRuntimeRepairReceiptEntry>,
  val disturbedRemedies: List<FeatureTaskRuntimeRepairDisturbedRemedy> = emptyList(),
) {
  init {
    if (contractVersion != FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION) {
      receiptError(
        "contract_version",
        "must be '$FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION'.",
      )
    }
    if (roundNumber < 1) {
      receiptError("round_number", "must be a positive integer.")
    }
    if (!GIT_COMMIT_SHA.matches(preFixCheckpointSha)) {
      receiptError(
        "pre_fix_checkpoint_sha",
        "must be a 40- or 64-character lowercase commit SHA.",
      )
    }
    if (entries.size > REPAIR_RECEIPT_MAX_ENTRIES) {
      receiptError("entries", "allows at most $REPAIR_RECEIPT_MAX_ENTRIES entries.")
    }
    if (disturbedRemedies.size > REPAIR_RECEIPT_MAX_DISTURBED_REMEDIES) {
      receiptError(
        "disturbed_remedies",
        "allows at most $REPAIR_RECEIPT_MAX_DISTURBED_REMEDIES declarations.",
      )
    }
    if (disturbedRemedies.map { normalizeIdentityPart(it.findingRef) }.distinct().size != disturbedRemedies.size) {
      receiptError("disturbed_remedies", "may declare each disturbed finding at most once.")
    }
  }

  @OpenBoundaryMap("Repair receipt at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "contract_version" to contractVersion,
    "round_number" to roundNumber,
    "pre_fix_checkpoint_sha" to preFixCheckpointSha,
    "entries" to entries.map(FeatureTaskRuntimeRepairReceiptEntry::toArtifactMap),
  ).apply {
    if (disturbedRemedies.isNotEmpty()) {
      put("disturbed_remedies", disturbedRemedies.map(FeatureTaskRuntimeRepairDisturbedRemedy::toArtifactMap))
    }
  }

  companion object {
    @OpenBoundaryMap("Repair receipt decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>, path: String): FeatureTaskRuntimeRepairReceipt {
      raw.requireOnlyReviewStateKeys(
        setOf("contract_version", "round_number", "pre_fix_checkpoint_sha", "entries", "disturbed_remedies"),
        path,
      )
      val entries = raw.requireReviewStateList("entries", path).mapIndexed { index, value ->
        FeatureTaskRuntimeRepairReceiptEntry.fromArtifactMap(
          value.asReviewStateMap("$path.entries[$index]"),
          "$path.entries[$index]",
        )
      }
      return anchoredToDecodePath(path) {
        FeatureTaskRuntimeRepairReceipt(
          contractVersion = raw.requireReviewStateString("contract_version", path),
          roundNumber = raw.requireReviewStateInt("round_number", path),
          preFixCheckpointSha = raw.requireReviewStateString("pre_fix_checkpoint_sha", path),
          entries = entries,
          disturbedRemedies = raw.optionalReviewStateList("disturbed_remedies", path)
            ?.mapIndexed { index, value ->
              FeatureTaskRuntimeRepairDisturbedRemedy.fromArtifactMap(
                value.asReviewStateMap("$path.disturbed_remedies[$index]"),
                "$path.disturbed_remedies[$index]",
              )
            }.orEmpty(),
        )
      }
    }

    /**
     * Entry-shape validation without the runtime-owned anchor. A run with no durable review state
     * has no remediation base or round to stamp, so the full receipt cannot be built — but the
     * sanitizer that keeps diff hunks and serialized payloads out of durable state lives on the
     * entries and still has to run.
     */
    @OpenBoundaryMap("Repair receipt entry-shape check from the durable workflow-artifact map")
    fun validateEntries(raw: Map<String, Any?>, path: String) {
      raw.requireReviewStateList("entries", path).forEachIndexed { index, value ->
        FeatureTaskRuntimeRepairReceiptEntry.fromArtifactMap(
          value.asReviewStateMap("$path.entries[$index]"),
          "$path.entries[$index]",
        )
      }
    }
  }
}

/**
 * The review pass this `implement_fix` round remediates: the completed pass count at
 * `implement_fix` entry. Never a phase-launch count.
 */
fun featureTaskRuntimeRemediationRoundNumber(completedPassCountAtImplementFixEntry: Int): Int {
  if (completedPassCountAtImplementFixEntry < 1) {
    receiptError(
      "round_number",
      "must be the completed review pass count at implement_fix entry and at least 1.",
    )
  }
  return completedPassCountAtImplementFixEntry
}

fun GoalSubtaskReviewState.upsertRepairReceipt(receipt: FeatureTaskRuntimeRepairReceipt): GoalSubtaskReviewState {
  val existing = repairReceipts.indexOfFirst { it.roundNumber == receipt.roundNumber }
  val updated = if (existing < 0) {
    (repairReceipts + receipt).sortedBy(FeatureTaskRuntimeRepairReceipt::roundNumber)
  } else {
    repairReceipts.toMutableList().apply { set(existing, receipt) }
  }
  return copy(repairReceipts = updated)
}

/**
 * Coverage for a round's carried findings. Prefer finding_id when the compact finding has one:
 * implement_fix is briefed with unresolved_blocker_findings (finding_id, severity, location,
 * expected_outcome), not the reducer-built label and sanitized text. Findings written before
 * finding_id was captured still match on the compact severity|label|text identity.
 */
fun FeatureTaskRuntimeRepairReceipt.coversCarriedFindings(
  carriedFindings: List<GoalSubtaskReviewCompactFinding>,
): Boolean = omittedCarriedFindings(carriedFindings).isEmpty()

/**
 * The carried findings this receipt never accounted for, in carried order. The runtime sends the
 * round back for exactly these, so it needs the identities rather than a boolean: naming them is
 * what lets the next attempt close the omission instead of guessing at it, and it is what makes a
 * non-shrinking omission set detectable.
 */
fun FeatureTaskRuntimeRepairReceipt.omittedCarriedFindings(
  carriedFindings: List<GoalSubtaskReviewCompactFinding>,
): List<GoalSubtaskReviewCompactFinding> {
  if (carriedFindings.isEmpty()) return emptyList()
  val reportedIds = entries.mapNotNull { entry -> entry.findingId?.let(::normalizeIdentityPart) }.toSet()
  val reportedCompact = entries.mapTo(linkedSetOf(), FeatureTaskRuntimeRepairReceiptEntry::findingIdentity)
  return carriedFindings.filterNot { carried ->
    val id = carried.findingId?.let(::normalizeIdentityPart)
    if (id != null) {
      id in reportedIds
    } else {
      compactReviewFindingIdentity(carried) in reportedCompact
    }
  }
}

/** Entries declaring the round tried and left the finding open. Each one is an operator dead end. */
fun FeatureTaskRuntimeRepairReceipt.attemptedUnresolvedEntries(): List<FeatureTaskRuntimeRepairReceiptEntry> =
  entries.filter { it.outcome == FeatureTaskRuntimeRepairOutcome.ATTEMPTED_UNRESOLVED }

internal fun compactReviewFindingIdentity(finding: GoalSubtaskReviewCompactFinding): String =
  listOf(finding.severity, finding.label, finding.text).joinToString("|", transform = ::normalizeIdentityPart)

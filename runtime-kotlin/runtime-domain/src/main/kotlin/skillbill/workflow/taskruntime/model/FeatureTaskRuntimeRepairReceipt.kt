package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskRuntimeRepairReceiptError
import skillbill.workflow.goal.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.goal.model.asReviewStateMap
import skillbill.workflow.goal.model.optionalReviewStateString
import skillbill.workflow.goal.model.requireOnlyReviewStateKeys
import skillbill.workflow.goal.model.requireReviewStateInt
import skillbill.workflow.goal.model.requireReviewStateList
import skillbill.workflow.goal.model.requireReviewStateString

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
        val rawSymbol = raw.requireReviewStateString("symbol", path)
        FeatureTaskRuntimeRepairConstruct(
          symbol = salvageCompactReceiptSymbol(rawSymbol) ?: rawSymbol,
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
  val outcome: FeatureTaskRuntimeRepairOutcome,
  val findingId: String,
  val noEditReason: String? = null,
  val unresolvedReason: String? = null,
) {
  init {
    requireReceiptIdentityText(findingId, "finding_id", REPAIR_RECEIPT_MAX_LABEL_UTF8_BYTES)
  }

  fun findingIdentity(): String = normalizeIdentityPart(findingId)

  @OpenBoundaryMap("Repair receipt entry at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = buildMap {
    put("finding_id", findingId)
    put("outcome", outcome.wireValue)
    noEditReason?.let { put("no_edit_reason", it) }
    unresolvedReason?.let { put("unresolved_reason", it) }
  }

  companion object {
    @OpenBoundaryMap("Repair receipt entry decode from the durable workflow-artifact map")
    fun fromArtifactMap(
      raw: Map<String, Any?>,
      path: String,
      observations: FeatureTaskRuntimeRepairReceiptDecodeObservations? = null,
    ): FeatureTaskRuntimeRepairReceiptEntry = anchoredToDecodePath(path) {
      FeatureTaskRuntimeRepairReceiptEntry(
        outcome = FeatureTaskRuntimeRepairOutcome.fromWire(raw.requireReviewStateString("outcome", path)),
        findingId = requireFindingRefAlias(raw, path),
        noEditReason = forwardOptionalReceiptReason(
          raw.optionalReviewStateString("no_edit_reason", path),
          "$path.no_edit_reason",
          REPAIR_RECEIPT_MAX_NO_EDIT_REASON_UTF8_BYTES,
          observations,
        ),
        unresolvedReason = forwardOptionalReceiptReason(
          raw.optionalReviewStateString("unresolved_reason", path),
          "$path.unresolved_reason",
          REPAIR_RECEIPT_MAX_UNRESOLVED_REASON_UTF8_BYTES,
          observations,
        ),
      )
    }
  }
}

data class FeatureTaskRuntimeRepairReceipt(
  val contractVersion: String = FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION,
  val roundNumber: Int,
  val preFixCheckpointSha: String,
  val entries: List<FeatureTaskRuntimeRepairReceiptEntry>,
) {
  init {
    if (contractVersion !in ACCEPTED_REPAIR_RECEIPT_CONTRACT_VERSIONS) {
      receiptError(
        "contract_version",
        "must be one of ${ACCEPTED_REPAIR_RECEIPT_CONTRACT_VERSIONS.joinToString { "'$it'" }}.",
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
  }

  @OpenBoundaryMap("Repair receipt at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "contract_version" to contractVersion,
    "round_number" to roundNumber,
    "pre_fix_checkpoint_sha" to preFixCheckpointSha,
    "entries" to entries.map(FeatureTaskRuntimeRepairReceiptEntry::toArtifactMap),
  )

  companion object {
    @OpenBoundaryMap("Repair receipt decode from the durable workflow-artifact map")
    fun fromArtifactMap(
      raw: Map<String, Any?>,
      path: String,
      observations: FeatureTaskRuntimeRepairReceiptDecodeObservations? = null,
    ): FeatureTaskRuntimeRepairReceipt {
      raw.requireOnlyReviewStateKeys(
        setOf("contract_version", "round_number", "pre_fix_checkpoint_sha", "entries", "disturbed_remedies"),
        path,
      )
      if (raw.containsKey("disturbed_remedies")) {
        receiptError(
          "disturbed_remedies",
          "is removed; records naming it must be regenerated.",
        )
      }
      val entries = raw.requireReviewStateList("entries", path).mapIndexed { index, value ->
        FeatureTaskRuntimeRepairReceiptEntry.fromArtifactMap(
          value.asReviewStateMap("$path.entries[$index]"),
          "$path.entries[$index]",
          observations,
        )
      }
      return anchoredToDecodePath(path) {
        FeatureTaskRuntimeRepairReceipt(
          contractVersion = raw.requireReviewStateString("contract_version", path),
          roundNumber = raw.requireReviewStateInt("round_number", path),
          preFixCheckpointSha = raw.requireReviewStateString("pre_fix_checkpoint_sha", path),
          entries = entries,
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
    fun validateEntries(
      raw: Map<String, Any?>,
      path: String,
      observations: FeatureTaskRuntimeRepairReceiptDecodeObservations? = null,
    ) {
      raw.requireReviewStateList("entries", path).forEachIndexed { index, value ->
        FeatureTaskRuntimeRepairReceiptEntry.fromArtifactMap(
          value.asReviewStateMap("$path.entries[$index]"),
          "$path.entries[$index]",
          observations,
        )
      }
    }
  }
}

private val ACCEPTED_REPAIR_RECEIPT_CONTRACT_VERSIONS: Set<String> = setOf(
  FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION,
)

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
 * Coverage for a round's carried findings. Match only on the briefing's finding ref
 * (`finding_id`). Label and text are decoration and never decide coverage. Carried findings must
 * carry a ref before coverage runs; [withStableFindingRefs] assigns one when review omitted it.
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
  val reportedIds = entries.mapTo(linkedSetOf()) { normalizeIdentityPart(it.findingId) }
  return carriedFindings.filterNot { carried ->
    val id = carried.findingId?.let(::normalizeIdentityPart)
    id != null && id in reportedIds
  }
}

fun FeatureTaskRuntimeRepairReceipt.attemptedUnresolvedEntries(): List<FeatureTaskRuntimeRepairReceiptEntry> =
  entries.filter { it.outcome == FeatureTaskRuntimeRepairOutcome.ATTEMPTED_UNRESOLVED }

internal fun compactReviewFindingIdentity(finding: GoalSubtaskReviewCompactFinding): String =
  listOf(finding.severity, finding.label, finding.text).joinToString("|", transform = ::normalizeIdentityPart)

private val FINDING_REF_ALIASES = listOf("finding_id", "finding_ref", "id", "ref")
private const val FINDING_REF_NUMERIC_WIDTH = 3

internal fun requireFindingRefAlias(raw: Map<String, Any?>, @Suppress("UnusedParameter") path: String): String {
  for (key in FINDING_REF_ALIASES) {
    val value = raw[key] as? String ?: continue
    val normalized = canonicalizeFindingRef(value)
    if (normalized != null) return normalized
  }
  val severity = raw["severity"] as? String
  val label = raw["label"] as? String
  val text = raw["text"] as? String
  if (!severity.isNullOrBlank() && !label.isNullOrBlank() && !text.isNullOrBlank()) {
    return "legacy:" + listOf(severity, label, text).joinToString("|", transform = ::normalizeIdentityPart)
  }
  receiptError(
    "finding_id",
    "must name the finding under finding_id (aliases finding_ref, id, ref also accepted).",
  )
}

internal fun canonicalizeFindingRef(raw: String): String? {
  val trimmed = raw.trim().removePrefix("#").trim()
  return trimmed.takeIf { it.isNotEmpty() }
}

fun withStableFindingRefs(findings: List<GoalSubtaskReviewCompactFinding>): List<GoalSubtaskReviewCompactFinding> {
  val used = findings.mapNotNull { it.findingId?.let(::normalizeIdentityPart) }.toMutableSet()
  var next = 1
  return findings.map { finding ->
    val existing = finding.findingId?.let(::canonicalizeFindingRef)
    if (existing != null) {
      used += normalizeIdentityPart(existing)
      finding.copy(findingId = existing)
    } else {
      var assigned: String
      do {
        assigned = "F-" + next.toString().padStart(FINDING_REF_NUMERIC_WIDTH, '0')
        next++
      } while (normalizeIdentityPart(assigned) in used)
      used += normalizeIdentityPart(assigned)
      finding.copy(findingId = assigned)
    }
  }
}

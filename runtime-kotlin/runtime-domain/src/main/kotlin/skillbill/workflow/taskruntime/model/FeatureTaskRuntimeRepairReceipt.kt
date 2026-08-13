package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskRuntimeRepairReceiptError
import java.nio.charset.StandardCharsets

const val REPAIR_RECEIPT_MAX_ENTRIES: Int = 50
const val REPAIR_RECEIPT_MAX_CONSTRUCTS_PER_ENTRY: Int = 16
const val REPAIR_RECEIPT_MAX_INTENT_UTF8_BYTES: Int = 256
const val REPAIR_RECEIPT_MAX_CONSTRUCT_SYMBOL_UTF8_BYTES: Int = 256
const val REPAIR_RECEIPT_MAX_CONSTRUCT_FILE_UTF8_BYTES: Int = 128
const val REPAIR_RECEIPT_MAX_NO_EDIT_REASON_UTF8_BYTES: Int = 256
const val REPAIR_RECEIPT_MAX_LABEL_UTF8_BYTES: Int = 256
const val REPAIR_RECEIPT_MAX_TEXT_UTF8_BYTES: Int = 256

private val GIT_COMMIT_SHA = Regex("^[0-9a-f]{40}(?:[0-9a-f]{24})?$")
private val COMPACT_SYMBOL = Regex("^[A-Za-z_][A-Za-z0-9_$-]*(?:\\.[A-Za-z_][A-Za-z0-9_$-]*)?$")
private val FILE_BASENAME = Regex("^[A-Za-z0-9_.-]+$")
private val IDENTITY_WHITESPACE = Regex("\\s+")
private val LINE_NUMBER = Regex("(?i)(?::\\d+|\\bL\\d+\\b|\\blines?\\s+\\d+)")
private val DIFF_HUNK = Regex("@@[^@]*@@|^(?:diff --git|\\+\\+\\+ |--- )")
private val SERIALIZED_PAYLOAD = Regex("\\{\\s*\"|\"\\s*:\\s*[\\[{\"]")
private const val CODE_FENCE: String = "```"

enum class FeatureTaskRuntimeRepairOutcome(val wireValue: String) {
  ADDRESSED("addressed"),
  NO_EDIT_REQUIRED("no_edit_required"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeRepairOutcome =
      entries.firstOrNull { it.wireValue == value }
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
      return FeatureTaskRuntimeRepairConstruct(
        symbol = raw.requireReviewStateString("symbol", path),
        file = raw.optionalReviewStateString("file", path),
      )
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
) {
  init {
    if (severity !in setOf("blocker", "major", "minor", "nit")) {
      receiptError("severity", "must be one of blocker, major, minor, nit.")
    }
    requireReceiptIdentityText(label, "label", REPAIR_RECEIPT_MAX_LABEL_UTF8_BYTES)
    requireReceiptIdentityText(text, "text", REPAIR_RECEIPT_MAX_TEXT_UTF8_BYTES)
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
      }
      FeatureTaskRuntimeRepairOutcome.NO_EDIT_REQUIRED -> {
        val reason = noEditReason
          ?: receiptError("no_edit_reason", "must be present when outcome is no_edit_required.")
        requireReceiptSanitizedText(reason, "no_edit_reason", REPAIR_RECEIPT_MAX_NO_EDIT_REASON_UTF8_BYTES)
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
  }

  companion object {
    @OpenBoundaryMap("Repair receipt entry decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>, path: String): FeatureTaskRuntimeRepairReceiptEntry {
      raw.requireOnlyReviewStateKeys(
        setOf("severity", "label", "text", "outcome", "constructs", "intent", "finding_id", "no_edit_reason"),
        path,
      )
      val constructs = raw.requireReviewStateList("constructs", path).mapIndexed { index, value ->
        FeatureTaskRuntimeRepairConstruct.fromArtifactMap(
          value.asReviewStateMap("$path.constructs[$index]"),
          "$path.constructs[$index]",
        )
      }
      return FeatureTaskRuntimeRepairReceiptEntry(
        severity = raw.requireReviewStateString("severity", path),
        label = raw.requireReviewStateString("label", path),
        text = raw.requireReviewStateString("text", path),
        outcome = FeatureTaskRuntimeRepairOutcome.fromWire(raw.requireReviewStateString("outcome", path)),
        constructs = constructs,
        intent = raw.requireReviewStateString("intent", path),
        findingId = raw.optionalReviewStateString("finding_id", path),
        noEditReason = raw.optionalReviewStateString("no_edit_reason", path),
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
  }

  @OpenBoundaryMap("Repair receipt at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "contract_version" to contractVersion,
    "round_number" to roundNumber,
    "pre_fix_checkpoint_sha" to preFixCheckpointSha,
    "entries" to entries.map(FeatureTaskRuntimeRepairReceiptEntry::toArtifactMap),
  )

  companion object {
    @OpenBoundaryMap("Repair receipt decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>, path: String): FeatureTaskRuntimeRepairReceipt {
      raw.requireOnlyReviewStateKeys(
        setOf("contract_version", "round_number", "pre_fix_checkpoint_sha", "entries"),
        path,
      )
      val entries = raw.requireReviewStateList("entries", path).mapIndexed { index, value ->
        FeatureTaskRuntimeRepairReceiptEntry.fromArtifactMap(
          value.asReviewStateMap("$path.entries[$index]"),
          "$path.entries[$index]",
        )
      }
      return FeatureTaskRuntimeRepairReceipt(
        contractVersion = raw.requireReviewStateString("contract_version", path),
        roundNumber = raw.requireReviewStateInt("round_number", path),
        preFixCheckpointSha = raw.requireReviewStateString("pre_fix_checkpoint_sha", path),
        entries = entries,
      )
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

fun GoalSubtaskReviewState.upsertRepairReceipt(
  receipt: FeatureTaskRuntimeRepairReceipt,
): GoalSubtaskReviewState {
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
): Boolean {
  if (carriedFindings.isEmpty()) return true
  val reportedIds = entries.mapNotNull { entry -> entry.findingId?.let(::normalizeIdentityPart) }.toSet()
  val reportedCompact = entries.mapTo(linkedSetOf(), FeatureTaskRuntimeRepairReceiptEntry::findingIdentity)
  return carriedFindings.all { carried ->
    val id = carried.findingId?.let(::normalizeIdentityPart)
    if (id != null) {
      id in reportedIds
    } else {
      compactReviewFindingIdentity(carried) in reportedCompact
    }
  }
}

/**
 * Replace each entry's severity/label/text with the last-pass compact finding that shares its
 * finding_id. implement_fix is briefed with finding_id, severity, location, and expected_outcome —
 * not the reducer-built label and sanitized text — so persist must stamp review-state identity
 * rather than store the agent echo.
 */
fun FeatureTaskRuntimeRepairReceipt.stampIdentityFromCompactFindings(
  lastPassFindings: List<GoalSubtaskReviewCompactFinding>,
): FeatureTaskRuntimeRepairReceipt {
  if (lastPassFindings.isEmpty()) return this
  val byId = lastPassFindings.mapNotNull { finding ->
    finding.findingId?.let { normalizeIdentityPart(it) to finding }
  }.toMap()
  if (byId.isEmpty()) return this
  return copy(
    entries = entries.map { entry ->
      val match = entry.findingId?.let { byId[normalizeIdentityPart(it)] } ?: return@map entry
      entry.copy(
        severity = match.severity,
        label = match.label,
        text = match.text,
        findingId = match.findingId,
      )
    },
  )
}

internal fun compactReviewFindingIdentity(finding: GoalSubtaskReviewCompactFinding): String =
  listOf(finding.severity, finding.label, finding.text).joinToString("|", transform = ::normalizeIdentityPart)

private fun normalizeIdentityPart(part: String): String =
  part.trim().lowercase().replace(IDENTITY_WHITESPACE, " ")

private fun requireReceiptSymbol(value: String, field: String) {
  requireReceiptSanitizedText(value, field, REPAIR_RECEIPT_MAX_CONSTRUCT_SYMBOL_UTF8_BYTES)
  if (value.contains('/') || value.contains('\\')) {
    receiptError(field, "must be a compact symbol (Type or Type.member), never a repository path.")
  }
  if (!COMPACT_SYMBOL.matches(value)) {
    receiptError(field, "must be a compact symbol (Type or Type.member), never a repository path.")
  }
}

private fun requireReceiptFileBasename(value: String, field: String) {
  requireUtf8Budget(value, field, REPAIR_RECEIPT_MAX_CONSTRUCT_FILE_UTF8_BYTES)
  if (value.contains('/') || value.contains('\\') || !FILE_BASENAME.matches(value)) {
    receiptError(field, "must be a file basename, never a repository path.")
  }
}

private fun requireReceiptIdentityText(value: String, field: String, maxUtf8Bytes: Int) {
  if (value.isBlank()) receiptError(field, "must be a non-blank string.")
  requireUtf8Budget(value, field, maxUtf8Bytes)
}

private fun requireReceiptSanitizedText(value: String, field: String, maxUtf8Bytes: Int) {
  if (value.isBlank()) receiptError(field, "must be a non-blank string.")
  requireUtf8Budget(value, field, maxUtf8Bytes)
  if (value.any(Char::isISOControl) || value.contains('\n') || value.contains('\r')) {
    receiptError(field, "must be a single line with no line break or control character.")
  }
  if (value.contains(CODE_FENCE)) {
    receiptError(field, "must not contain a code fence.")
  }
  if (LINE_NUMBER.containsMatchIn(value)) {
    receiptError(field, "must not contain a line number.")
  }
  if (DIFF_HUNK.containsMatchIn(value) || SERIALIZED_PAYLOAD.containsMatchIn(value)) {
    receiptError(field, "must not contain a diff hunk, serialized payload, or source body.")
  }
}

private fun requireUtf8Budget(value: String, field: String, maxUtf8Bytes: Int) {
  val bytes = value.toByteArray(StandardCharsets.UTF_8).size
  if (bytes > maxUtf8Bytes) {
    receiptError(field, "allows at most $maxUtf8Bytes UTF-8 bytes.")
  }
}

private fun receiptError(fieldPath: String, payloadFreeReason: String): Nothing =
  throw InvalidFeatureTaskRuntimeRepairReceiptError(
    fieldPath = fieldPath,
    reason = payloadFreeReason,
    payloadFreeReason = payloadFreeReason,
  )

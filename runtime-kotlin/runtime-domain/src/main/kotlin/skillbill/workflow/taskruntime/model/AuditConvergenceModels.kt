package skillbill.workflow.taskruntime.model

import java.security.MessageDigest

const val AUDIT_GENERATION_CONTRACT_VERSION: String = "0.1"
const val MAX_AUDIT_GENERATION_SATISFIED_CRITERIA: Int = 100
const val MAX_AUDIT_GENERATION_GAPS: Int = 50
const val MAX_AUDIT_GENERATION_REPAIR_ITEMS: Int = 100

enum class AuditGapStatus { NEW, RECURRING, RESOLVED, SUPERSEDED, STILL_OPEN }

data class AuditGeneration(
  val generationId: String,
  val workflowId: String,
  val generation: Int,
  val repositoryCheckpoint: RepositoryCheckpoint,
  val satisfiedCriterionRefs: List<String>,
  val gaps: List<AuditGap>,
  val repairBatch: AuditRepairBatch?,
  val createdAt: String,
) {
  init {
    require(generationId.isSafeIdentity()) { "generation_id must be a safe identity." }
    require(workflowId.isSafeIdentity()) { "workflow_id must be a safe identity." }
    require(generation > 0) { "generation must be positive, was $generation." }
    require(satisfiedCriterionRefs.size <= MAX_AUDIT_GENERATION_SATISFIED_CRITERIA) {
      "satisfied_criterion_refs allows at most $MAX_AUDIT_GENERATION_SATISFIED_CRITERIA entries, " +
        "had ${satisfiedCriterionRefs.size}."
    }
    require(gaps.size <= MAX_AUDIT_GENERATION_GAPS) {
      "gaps allows at most $MAX_AUDIT_GENERATION_GAPS entries, had ${gaps.size}."
    }
    satisfiedCriterionRefs.forEach { ref ->
      require(ACCEPTANCE_CRITERION_REF.matches(ref)) {
        "satisfied_criterion_refs entry '$ref' must use canonical format 'AC-NNN'."
      }
    }
    runCatching { java.time.Instant.parse(createdAt) }.getOrNull()
      ?: throw IllegalArgumentException("created_at must be a valid ISO-8601 timestamp, was '$createdAt'.")
  }
}

data class RepositoryCheckpoint(
  val fingerprint: String,
  val evidenceRef: String,
) {
  init {
    require(fingerprint.matches(Regex("[a-f0-9]{64}"))) {
      "repository_fingerprint must be a 64-character hex string, was '$fingerprint'."
    }
    require(evidenceRef.length in 1..CONVERGENCE_REFERENCE_MAX_LENGTH) {
      "evidence_ref allows at most $CONVERGENCE_REFERENCE_MAX_LENGTH characters, had ${evidenceRef.length}."
    }
  }
}

data class AuditGap(
  val gapId: String,
  val acceptanceCriterionRef: String,
  val acceptanceCriterionText: String,
  val failureEvidence: FeatureTaskRuntimeEvidence,
  val diagnosis: String,
  val affectedBoundary: String,
  val status: AuditGapStatus,
  val recurrence: Int,
  val firstSeenGeneration: Int,
) {
  init {
    requireNonBlank(gapId, "gap_id")
    requireGapIdPattern(gapId)
    requireNonBlank(acceptanceCriterionRef, "acceptance_criterion_ref")
    require(ACCEPTANCE_CRITERION_REF.matches(acceptanceCriterionRef)) {
      "acceptance_criterion_ref '$acceptanceCriterionRef' must use canonical format 'AC-NNN'."
    }
    requireNonBlank(acceptanceCriterionText, "acceptance_criterion_text")
    requireNonBlank(diagnosis, "diagnosis")
    requireNonBlank(affectedBoundary, "affected_boundary")
    require(recurrence >= 0) { "recurrence must be non-negative, was $recurrence." }
    require(firstSeenGeneration > 0) { "first_seen_generation must be positive, was $firstSeenGeneration." }
    when (status) {
      AuditGapStatus.NEW -> require(recurrence == 0) { "New gaps must have recurrence 0, was $recurrence." }
      AuditGapStatus.RECURRING -> require(recurrence > 0) {
        "Recurring gaps must have recurrence > 0, was $recurrence."
      }
      AuditGapStatus.RESOLVED,
      AuditGapStatus.SUPERSEDED,
      -> {}
      AuditGapStatus.STILL_OPEN -> {}
    }
  }
}

data class AuditRepairBatch(
  val batchId: String,
  val generationId: String,
  val repairItems: List<AuditRepairItem>,
  val dependencies: Map<String, List<String>>,
  val isActive: Boolean,
) {
  init {
    require(batchId.isSafeIdentity()) { "batch_id must be a safe identity." }
    require(generationId.isSafeIdentity()) { "generation_id must be a safe identity." }
    require(repairItems.size <= MAX_AUDIT_GENERATION_REPAIR_ITEMS) {
      "repair_items allows at most $MAX_AUDIT_GENERATION_REPAIR_ITEMS entries, had ${repairItems.size}."
    }
    val itemIds = repairItems.mapTo(linkedSetOf()) { it.itemId }
    require(itemIds.size == repairItems.size) { "repair_item_ids must be unique within a batch." }
    dependencies.forEach { (itemId, deps) ->
      require(itemId in itemIds) { "Dependency key '$itemId' must reference a repair_item in this batch." }
      deps.forEach { dep ->
        require(dep in itemIds) { "Dependency '$dep' must reference a repair_item in this batch." }
      }
    }
  }
}

data class AuditRepairItem(
  val itemId: String,
  val gapId: String,
  val intendedOutcome: String,
  val implementationActions: List<String>,
  val affectedPathsOrSymbols: List<String>,
  val requiredVerification: List<String>,
  val dependencies: List<String>,
) {
  init {
    requireNonBlank(itemId, "item_id")
    require(REPAIR_ITEM_ID.matches(itemId)) {
      "item_id '$itemId' must be the stable ordered child '<gap-id>-item-<ordinal>'."
    }
    requireNonBlank(gapId, "gap_id")
    requireGapIdPattern(gapId)
    require(itemId.startsWith("$gapId-item")) {
      "item_id '$itemId' must belong to gap '$gapId'."
    }
    requireNonBlank(intendedOutcome, "intended_outcome")
    requireNonBlankList(implementationActions, "implementation_actions")
    requireCompactList(implementationActions, "implementation_actions", MAX_COMPACT_LIST_ITEMS)
    requireNonBlankList(affectedPathsOrSymbols, "affected_paths_or_symbols")
    requireCompactList(affectedPathsOrSymbols, "affected_paths_or_symbols", MAX_PATH_LIST_ITEMS)
    requireNonBlankList(requiredVerification, "required_verification")
    requireCompactList(requiredVerification, "required_verification", MAX_COMPACT_LIST_ITEMS)
    require(dependencies.size <= MAX_COMPACT_LIST_ITEMS) { "dependencies exceeds the durable item limit." }
    require(dependencies.all(String::isNotBlank)) { "dependencies entries must be nonblank." }
  }
}

data class AuditGapDisposition(
  val gapId: String,
  val status: AuditGapStatus,
  val evidence: FeatureTaskRuntimeEvidence,
  val dispositionGeneration: Int,
  val supersededByGeneration: Int? = null,
) {
  init {
    requireNonBlank(gapId, "gap_id")
    requireGapIdPattern(gapId)
    val expectedObservation = when (status) {
      AuditGapStatus.RESOLVED -> FeatureTaskRuntimeEvidence.Observation.RESOLUTION_VERIFIED
      AuditGapStatus.RECURRING -> FeatureTaskRuntimeEvidence.Observation.RECURRENCE_VERIFIED
      AuditGapStatus.SUPERSEDED -> FeatureTaskRuntimeEvidence.Observation.FIX_VERIFIED
      AuditGapStatus.NEW,
      AuditGapStatus.STILL_OPEN,
      -> throw IllegalArgumentException("Gap disposition status '$status' requires verification evidence.")
    }
    require(evidence.observation == expectedObservation) {
      "Gap disposition '$gapId' evidence.observation must be '${expectedObservation.wire()}' when " +
        "status is '${status.wire()}', was '${evidence.observation.wire()}'."
    }
    require(dispositionGeneration > 0) { "disposition_generation must be positive, was $dispositionGeneration." }
    if (status == AuditGapStatus.SUPERSEDED) {
      require(supersededByGeneration != null && supersededByGeneration > dispositionGeneration) {
        "superseded_by_generation must be greater than disposition_generation when status is SUPERSEDED."
      }
    }
  }
}

object AuditGenerationIdentities {
  fun generationId(workflowId: String, generation: Int): String =
    "audit-generation:${digest("$workflowId|$generation")}"

  fun gapId(criterionRef: String, generation: Int): String = "${criterionRef.lowercase()}-gap-$generation"

  fun repairItemId(gapId: String, ordinal: Int): String = "$gapId-item-$ordinal"

  private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }
}

fun AuditGapStatus.wire(): String = name.lowercase()

private val ACCEPTANCE_CRITERION_REF = Regex("AC-[0-9]{3}")
private val GAP_ID = Regex("ac-[0-9]{3,}-gap-[1-9][0-9]*")
private val REPAIR_ITEM_ID = Regex("ac-[0-9]{3,}-gap-[1-9][0-9]*-item-[1-9][0-9]*")

private fun requireGapIdPattern(gapId: String) {
  require(GAP_ID.matches(gapId)) {
    "gap_id '$gapId' must be the stable criterion-generation identifier '<criterion-ref>-gap-<generation>' " +
      "in canonical lowercase, for example 'ac-005-gap-1'."
  }
}

private fun String.isSafeIdentity(): Boolean =
  length in 1..CONVERGENCE_ID_MAX_LENGTH && matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*"))

private fun requireNonBlank(value: String, field: String) {
  require(value.isNotBlank()) { "$field must be nonblank." }
  requireDurableText(value, field)
}

private fun requireNonBlankList(values: List<String>, field: String) {
  require(values.isNotEmpty()) { "$field must contain at least one entry, was empty." }
  require(values.all(String::isNotBlank)) {
    "$field entries must be nonblank; blank at ${values.indexOfFirst(String::isBlank)}."
  }
}

private fun requireCompactList(values: List<String>, field: String, maximumItems: Int) {
  require(values.size <= maximumItems) {
    "$field allows at most $maximumItems entries, had ${values.size}."
  }
  values.forEach { requireDurableText(it, "$field entry") }
}

private fun requireDurableText(value: String, field: String) {
  require(value.length <= MAX_AUDIT_REPAIR_TEXT_LENGTH) {
    "$field allows at most $MAX_AUDIT_REPAIR_TEXT_LENGTH characters, had ${value.length}."
  }
  require(value.none(Char::isISOControl)) {
    "$field must be a single-line durable value; \"${value.preview()}\" contains a line break or control character."
  }
  require(value.none { it == '`' }) {
    "$field must not contain code-fence or quoted-source syntax; remove the backticks from \"${value.preview()}\"."
  }
  require(!SERIALIZED_PAYLOAD.containsMatchIn(value)) {
    "$field must be a short single-line description, not serialized, patch, or tool-output syntax; " +
      "\"${value.preview()}\" looks like a pasted payload."
  }
  require(!SUMMARY_ROLE_PREFIX.containsMatchIn(value)) {
    "$field must not contain a prompt transcript; \"${value.preview()}\" starts with a role prefix."
  }
}

private val SERIALIZED_PAYLOAD = Regex(
  "\\{\\s*\"|\"\\s*:\\s*[\\[{\"]|@@[^@]*@@|^(?:diff --git|\\+\\+\\+ |--- )",
)

private val SUMMARY_ROLE_PREFIX = Regex(
  "(?i)^\\s*(system|user|assistant|developer|tool)(?:\\s+(?:prompt|message|output))?\\s*:",
)

private const val REJECTION_PREVIEW_CHARS: Int = 80

private fun String.preview(): String {
  val flattened = map { if (it.isISOControl()) ' ' else it }.joinToString("")
  return if (flattened.length <= REJECTION_PREVIEW_CHARS) {
    flattened
  } else {
    flattened.take(REJECTION_PREVIEW_CHARS) + "…"
  }
}

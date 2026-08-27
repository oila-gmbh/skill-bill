@file:Suppress("TooManyFunctions", "MaxLineLength")

package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

sealed interface FeatureTaskRuntimePlanningProjection {
  val projectionKind: FeatureTaskRuntimeProjectionKind

  fun toProjectionFields(): List<FeatureTaskRuntimeHandoffProjectionField>
}

enum class FeatureTaskRuntimeProjectionKind(val wireValue: String) {
  IMPLEMENTATION_RECEIPT("implementation_receipt"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeProjectionKind = entries.firstOrNull { it.wireValue == value }
      ?: throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
        sourceLabel = "<projection_kind>",
        reason = "unknown projection_kind '$value'; expected one of ${entries.joinToString { it.wireValue }}.",
      )
  }
}

object FeatureTaskRuntimePlanningProjectionContract {
  const val IMPLEMENTATION_RECEIPT_ID: String = "feature_task_runtime.implementation_receipt"
  const val SHARED_REVIEW_EVIDENCE_ID: String = "feature_task_runtime.shared_review_evidence"
  val VERSION: String = FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION

  fun producedProjectionKindFor(phaseId: String): FeatureTaskRuntimeProjectionKind? = when (phaseId) {
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT ->
      FeatureTaskRuntimeProjectionKind.IMPLEMENTATION_RECEIPT
    else -> null
  }
}

private val REPO_PATH_PATTERN = Regex("^(?!/)(?!.*\\\\)(?!.*(?:^|/)\\.\\.(?:/|$)).+$")
private val TASK_ID_PATTERN = Regex("^[a-z][a-z0-9-]*$")
private const val REPO_PATH_MAX_LENGTH: Int = 1024
private const val TEXT_MAX_LENGTH: Int = 4096

const val FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT: Int = 128

const val FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT: Int = 512

data class FeatureTaskRuntimeImplementationReceipt(
  val completedTaskIds: List<String>,
  val changedPaths: List<String>,
  val testsAdded: List<String> = emptyList(),
  val testsUpdated: List<String> = emptyList(),
  val testsExecuted: List<FeatureTaskRuntimeTestExecution>,
  val deviations: List<FeatureTaskRuntimeDeviation> = emptyList(),
  val unresolvedItems: List<String> = emptyList(),
  val reconciliationEvidence: FeatureTaskRuntimeReconciliationEvidence,
  val repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
) : FeatureTaskRuntimePlanningProjection {
  override val projectionKind: FeatureTaskRuntimeProjectionKind =
    FeatureTaskRuntimeProjectionKind.IMPLEMENTATION_RECEIPT

  init {
    requireListSize(completedTaskIds.size, "completed_task_ids")
    completedTaskIds.forEach {
      require(TASK_ID_PATTERN.matches(it)) { "completed_task_ids entry '$it' is not a valid task id." }
    }
    requireListSize(testsExecuted.size, "tests_executed")
    requireListSize(deviations.size, "deviations")
    requireChangedPaths(changedPaths)
    requireNonBlankStrings(testsAdded, "tests_added", allowRepoPath = true)
    requireNonBlankStrings(testsUpdated, "tests_updated", allowRepoPath = true)
    requireNonBlankStrings(unresolvedItems, "unresolved_items")
    val executedNames = testsExecuted.map { it.name }
    require(executedNames.distinct().size == executedNames.size) {
      "tests_executed names must be unique, duplicated ${executedNames.duplicates()}."
    }
  }

  override fun toProjectionFields(): List<FeatureTaskRuntimeHandoffProjectionField> = listOf(
    field(FIELD_COMPLETED_TASK_IDS, FeatureTaskRuntimeHandoffProjectionValue.TextList(completedTaskIds)),
    field(FIELD_CHANGED_PATHS, FeatureTaskRuntimeHandoffProjectionValue.TextList(changedPaths)),
    field(FIELD_TESTS_ADDED, FeatureTaskRuntimeHandoffProjectionValue.TextList(testsAdded)),
    field(FIELD_TESTS_UPDATED, FeatureTaskRuntimeHandoffProjectionValue.TextList(testsUpdated)),
    field(
      FIELD_TESTS_EXECUTED,
      FeatureTaskRuntimeHandoffProjectionValue.TextList(testsExecuted.map { "${it.name}=${it.outcome.wireValue}" }),
    ),
    field(
      FIELD_DEVIATIONS,
      FeatureTaskRuntimeHandoffProjectionValue.TextList(deviations.map { "${it.ref}: ${it.note}" }),
    ),
    field(FIELD_UNRESOLVED_ITEMS, FeatureTaskRuntimeHandoffProjectionValue.TextList(unresolvedItems)),
    field(
      FIELD_RECONCILIATION_EVIDENCE,
      FeatureTaskRuntimeHandoffProjectionValue.Text(reconciliationEvidence.evidence),
    ),
  ).let { fields ->
    if (repositoryCheckpoint == null) {
      fields
    } else {
      fields + field(
        FIELD_REPOSITORY_CHECKPOINT,
        FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
          kind = FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT,
          value = repositoryCheckpoint.fingerprint,
        ),
      )
    }
  }

  companion object {
    val DECLARED_FIELD_NAMES: List<String> = listOf(
      FIELD_COMPLETED_TASK_IDS,
      FIELD_CHANGED_PATHS,
      FIELD_TESTS_ADDED,
      FIELD_TESTS_UPDATED,
      FIELD_TESTS_EXECUTED,
      FIELD_DEVIATIONS,
      FIELD_UNRESOLVED_ITEMS,
      FIELD_RECONCILIATION_EVIDENCE,
      FIELD_REPOSITORY_CHECKPOINT,
    )

    const val FIELD_COMPLETED_TASK_IDS: String = "completed_task_ids"
    const val FIELD_CHANGED_PATHS: String = "changed_paths"
    const val FIELD_TESTS_ADDED: String = "tests_added"
    const val FIELD_TESTS_UPDATED: String = "tests_updated"
    const val FIELD_TESTS_EXECUTED: String = "tests_executed"
    const val FIELD_DEVIATIONS: String = "deviations"
    const val FIELD_UNRESOLVED_ITEMS: String = "unresolved_items"
    const val FIELD_RECONCILIATION_EVIDENCE: String = "reconciliation_evidence"
    const val FIELD_REPOSITORY_CHECKPOINT: String = "repository_checkpoint"
  }
}

fun featureTaskRuntimeRenderOpenWorkItem(value: Any?): String? = when (value) {
  null -> null
  is String -> value.trim().takeIf(String::isNotBlank)
  is Map<*, *> -> {
    val ref = (value["ref"] as? String)?.trim().orEmpty()
    val note = (value["note"] as? String)?.trim().orEmpty()
    when {
      ref.isNotBlank() && note.isNotBlank() -> "$ref: $note"
      ref.isNotBlank() -> ref
      note.isNotBlank() -> note
      else -> value.toString().trim().takeIf(String::isNotBlank)
    }
  }
  else -> value.toString().trim().takeIf(String::isNotBlank)
}

data class FeatureTaskRuntimeTestExecution(
  val name: String,
  val outcome: FeatureTaskRuntimeTestOutcome,
) {
  init {
    requireRepoPath(name, "tests_executed.name")
  }
}

enum class FeatureTaskRuntimeTestOutcome(val wireValue: String) {
  PASSED("passed"),
  FAILED("failed"),
  SKIPPED("skipped"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeTestOutcome = entries.firstOrNull { it.wireValue == value }
      ?: throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
        sourceLabel = "<tests_executed.outcome>",
        reason = "unknown test outcome '$value'; expected passed, failed, or skipped.",
      )
  }
}

data class FeatureTaskRuntimeDeviation(
  val ref: String,
  val note: String,
) {
  init {
    require(ref.isNotBlank()) { "FeatureTaskRuntimeDeviation.ref must be non-blank." }
    require(ref.length <= TEXT_MAX_LENGTH) { "FeatureTaskRuntimeDeviation.ref exceeds $TEXT_MAX_LENGTH chars." }
    require(note.isNotBlank()) { "FeatureTaskRuntimeDeviation.note must be non-blank." }
    require(note.length <= TEXT_MAX_LENGTH) { "FeatureTaskRuntimeDeviation.note exceeds $TEXT_MAX_LENGTH chars." }
  }
}

data class FeatureTaskRuntimeReconciliationEvidence(
  val reconciled: Boolean,
  val evidence: String,
) {
  init {
    require(reconciled) {
      "FeatureTaskRuntimeReconciliationEvidence.reconciled must be true; an un-reconciled receipt cannot be delivered."
    }
    require(evidence.isNotBlank()) { "FeatureTaskRuntimeReconciliationEvidence.evidence must be non-blank." }
    require(evidence.length <= TEXT_MAX_LENGTH) {
      "FeatureTaskRuntimeReconciliationEvidence.evidence exceeds $TEXT_MAX_LENGTH chars."
    }
  }
}

private fun field(
  name: String,
  value: FeatureTaskRuntimeHandoffProjectionValue,
): FeatureTaskRuntimeHandoffProjectionField = FeatureTaskRuntimeHandoffProjectionField(name = name, value = value)

private fun requireListSize(size: Int, field: String, max: Int = FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT) {
  require(size <= max) { "$field allows at most $max entries, had $size." }
}

private fun requireNonBlankStrings(values: List<String>, field: String, allowRepoPath: Boolean = false) {
  requireListSize(values.size, field)
  values.forEach { value ->
    require(value.isNotBlank()) { "$field entries must be non-blank." }
    require(value.length <= REPO_PATH_MAX_LENGTH) { "$field entry exceeds $REPO_PATH_MAX_LENGTH chars." }
    if (allowRepoPath) requireRepoPath(value, "$field entry")
  }
}

private fun requireRepoPath(value: String, field: String) {
  require(REPO_PATH_PATTERN.matches(value)) {
    "$field '$value' must be a repository-relative path: no leading slash, no backslash, no `..` segment."
  }
}

private fun requireChangedPaths(paths: List<String>) {
  requireListSize(paths.size, "changed_paths", FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT)
  val duplicates = paths.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
  require(duplicates.isEmpty()) { "changed_paths must be unique, duplicated $duplicates." }
  paths.forEach { requireRepoPath(it, "changed_paths entry") }
}

private fun List<String>.duplicates(): List<String> =
  groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()

@OpenBoundaryMap("Feature-task-runtime planning projection parse from the schema-validated phase-output wire map")
fun featureTaskRuntimePlanningProjectionFromEnvelope(
  envelope: Map<String, Any?>,
  producingPhaseId: String,
  expectedKind: FeatureTaskRuntimeProjectionKind,
  schemaValidator: FeatureTaskRuntimePlanningProjectionValidator,
): FeatureTaskRuntimePlanningProjection =
  featureTaskRuntimePlanningProjectionParseFromEnvelope(envelope, producingPhaseId, expectedKind, schemaValidator)
    .projection

internal data class FeatureTaskRuntimePlanningProjectionParse(
  val projection: FeatureTaskRuntimePlanningProjection,
  val canonicalizations: List<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
)

@Suppress("ThrowsCount")
internal fun featureTaskRuntimePlanningProjectionParseFromEnvelope(
  envelope: Map<String, Any?>,
  producingPhaseId: String,
  expectedKind: FeatureTaskRuntimeProjectionKind,
  schemaValidator: FeatureTaskRuntimePlanningProjectionValidator,
): FeatureTaskRuntimePlanningProjectionParse {
  val sourceLabel = "$producingPhaseId#produced_outputs"
  val produced = envelope.stringAnyMap("produced_outputs")
    ?: throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
      sourceLabel = sourceLabel,
      reason = "produced_outputs is missing or not an object.",
    )
  val kind = produced["projection_kind"]?.toString()
    ?: throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
      sourceLabel = sourceLabel,
      reason = "produced_outputs.projection_kind is missing; a bounded projection requires it.",
    )
  val declaredKind = FeatureTaskRuntimeProjectionKind.fromWire(kind)
  if (declaredKind != expectedKind) {
    throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
      sourceLabel = sourceLabel,
      reason = "the consuming declaration expects projection_kind '${expectedKind.wireValue}' but the producer " +
        "emitted '${declaredKind.wireValue}'.",
    )
  }
  requirePinnedContractVersion(produced, sourceLabel)
  val canonicalization = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)
  val canonical = canonicalization.canonical
  schemaValidator.validatePlanningProjection(canonical, sourceLabel)
  return try {
    val projection = when (expectedKind) {
      FeatureTaskRuntimeProjectionKind.IMPLEMENTATION_RECEIPT ->
        FeatureTaskRuntimeImplementationReceipt.fromMap(canonical)
    }
    FeatureTaskRuntimePlanningProjectionParse(projection, canonicalization.diagnostics)
  } catch (error: IllegalArgumentException) {
    throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
      sourceLabel = sourceLabel,
      reason = error.message ?: "the payload violates a typed planning-projection rule.",
      cause = error,
    )
  }
}

private fun requirePinnedContractVersion(produced: Map<String, Any?>, sourceLabel: String) {
  val version = produced["contract_version"]?.toString()
  if (version != FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION) {
    throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
      sourceLabel = sourceLabel,
      reason = "produced_outputs.contract_version must be " +
        "'$FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION', was ${version?.let { "'$it'" } ?: "absent"}.",
    )
  }
}

@Suppress("ThrowsCount")
private fun FeatureTaskRuntimeImplementationReceipt.Companion.fromMap(
  map: Map<String, Any?>,
): FeatureTaskRuntimeImplementationReceipt {
  val checkpointMap = map.stringAnyMap("repository_checkpoint")
  val reconciliationMap = map.stringAnyMap("reconciliation_evidence")
    ?: throw malformed("reconciliation_evidence", "must be an object")
  val rawExecuted = map["tests_executed"] as? List<*>
    ?: throw malformed("tests_executed", "must be a list")
  val executed = rawExecuted.mapIndexed { index, raw ->
    val execMap = (raw as? Map<*, *>)?.entries?.filter { it.key is String }
      ?.associate { it.key as String to it.value }
      ?: throw malformed("tests_executed[$index]", "must be an object")
    FeatureTaskRuntimeTestExecution(
      name = execMap.requireString("name", "tests_executed[$index].name"),
      outcome = FeatureTaskRuntimeTestOutcome.fromWire(
        execMap["outcome"]?.toString().orEmpty(),
      ),
    )
  }
  val rawDeviations = (map["deviations"] as? List<*>).orEmpty()
  val deviations = rawDeviations.mapIndexed { index, raw ->
    val devMap = (raw as? Map<*, *>)?.entries?.filter { it.key is String }
      ?.associate { it.key as String to it.value }
      ?: throw malformed("deviations[$index]", "must be an object")
    FeatureTaskRuntimeDeviation(
      ref = devMap.requireString("ref", "deviations[$index].ref"),
      note = devMap.requireString("note", "deviations[$index].note"),
    )
  }
  return FeatureTaskRuntimeImplementationReceipt(
    completedTaskIds = map.optionalStringList("completed_task_ids"),
    changedPaths = map.optionalStringList("changed_paths"),
    testsAdded = map.optionalStringList("tests_added"),
    testsUpdated = map.optionalStringList("tests_updated"),
    testsExecuted = executed,
    deviations = deviations,
    unresolvedItems = map.openWorkItemList("unresolved_items"),
    reconciliationEvidence = FeatureTaskRuntimeReconciliationEvidence(
      reconciled = (reconciliationMap["reconciled"] as? Boolean)
        ?: throw malformed("reconciliation_evidence.reconciled", "must be a boolean"),
      evidence = reconciliationMap.firstString("evidence"),
    ),
    repositoryCheckpoint = checkpointMap?.let { map ->
      FeatureTaskRuntimeRepositoryCheckpoint(
        fingerprint = map.requireString("fingerprint", "repository_checkpoint.fingerprint"),
        baseRef = map["base_ref"]?.toString()?.takeIf(String::isNotBlank),
        headRef = map["head_ref"]?.toString()?.takeIf(String::isNotBlank),
        workingTreeOwnedPaths = map.optionalStringList("working_tree_owned_paths"),
      )
    },
  )
}

data class FeatureTaskRuntimeSharedReviewEvidenceReference(
  val storePath: String,
  val checkpointFingerprint: String,
  val baseRef: String?,
  val headRef: String?,
  val fileHunkIndex: List<String>,
) {
  init {
    require(storePath.isNotBlank()) {
      "FeatureTaskRuntimeSharedReviewEvidenceReference.storePath must be non-blank; a reference that " +
        "cannot name its artifact is not dereferenceable."
    }
    require(checkpointFingerprint.isNotBlank()) {
      "FeatureTaskRuntimeSharedReviewEvidenceReference.checkpointFingerprint must be non-blank; the " +
        "fingerprint is the artifact's only reuse key."
    }
    require(fileHunkIndex.size <= FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT) {
      "FeatureTaskRuntimeSharedReviewEvidenceReference.fileHunkIndex allows at most " +
        "$FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT entries, had ${fileHunkIndex.size}."
    }
  }

  fun toProjectionFields(): List<FeatureTaskRuntimeHandoffProjectionField> = listOfNotNull(
    FeatureTaskRuntimeHandoffProjectionField(
      name = FIELD_STORE_PATH,
      value = FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
        kind = FeatureTaskRuntimeCompactReferenceKind.PRIVATE_EVIDENCE_ARTIFACT,
        value = storePath,
      ),
    ),
    FeatureTaskRuntimeHandoffProjectionField(
      name = FIELD_CHECKPOINT_FINGERPRINT,
      value = FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
        kind = FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT,
        value = checkpointFingerprint,
      ),
    ),
    baseRef?.let {
      FeatureTaskRuntimeHandoffProjectionField(FIELD_BASE_REF, FeatureTaskRuntimeHandoffProjectionValue.Text(it))
    },
    headRef?.let {
      FeatureTaskRuntimeHandoffProjectionField(FIELD_HEAD_REF, FeatureTaskRuntimeHandoffProjectionValue.Text(it))
    },
    FeatureTaskRuntimeHandoffProjectionField(
      name = FIELD_FILE_HUNK_INDEX,
      value = FeatureTaskRuntimeHandoffProjectionValue.TextList(fileHunkIndex),
    ),
  )

  companion object {
    const val FIELD_STORE_PATH: String = "store_path"
    const val FIELD_CHECKPOINT_FINGERPRINT: String = "checkpoint_fingerprint"
    const val FIELD_BASE_REF: String = "base_ref"
    const val FIELD_HEAD_REF: String = "head_ref"
    const val FIELD_FILE_HUNK_INDEX: String = "file_hunk_index"

    val DECLARED_FIELD_NAMES: List<String> = listOf(
      FIELD_STORE_PATH,
      FIELD_CHECKPOINT_FINGERPRINT,
      FIELD_BASE_REF,
      FIELD_HEAD_REF,
      FIELD_FILE_HUNK_INDEX,
    )

    fun of(
      storePath: String,
      artifact: FeatureTaskRuntimeSharedEvidenceArtifact,
    ): FeatureTaskRuntimeSharedReviewEvidenceReference {
      val hunkCounts = artifact.hunks.groupingBy { it.path }.eachCount()
      val entries = artifact.files.map { file ->
        "${file.changeKind} ${file.path} hunks=${hunkCounts[file.path] ?: 0}"
      }
      val bounded = if (entries.size <= FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT) {
        entries
      } else {
        entries.take(FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT - 1) +
          "omitted ${entries.size - (FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT - 1)} further changed files"
      }
      return FeatureTaskRuntimeSharedReviewEvidenceReference(
        storePath = storePath,
        checkpointFingerprint = artifact.fingerprint,
        baseRef = artifact.baseRef?.takeIf(String::isNotBlank),
        headRef = artifact.headRef?.takeIf(String::isNotBlank),
        fileHunkIndex = bounded,
      )
    }
  }
}

private fun malformed(field: String, reason: String): Nothing =
  throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
    sourceLabel = "<$field>",
    reason = reason,
  )

private fun Map<String, Any?>.requireString(key: String, label: String): String =
  firstString(key).ifBlank { throw malformed(label, "must be a non-blank string") }

private fun Map<String, Any?>.optionalStringList(key: String): List<String> {
  val raw = this[key] ?: return emptyList()
  val list = raw as? List<*> ?: throw malformed(key, "must be a list")
  return list.map { value ->
    value?.toString()?.trim()?.takeIf(String::isNotBlank)
      ?: throw malformed(key, "entries must be non-blank strings")
  }
}

private fun Map<String, Any?>.openWorkItemList(key: String): List<String> {
  val raw = this[key] ?: return emptyList()
  val list = raw as? List<*> ?: throw malformed(key, "must be a list")
  return list.mapIndexed { index, value ->
    featureTaskRuntimeRenderOpenWorkItem(value)
      ?: throw malformed("$key[$index]", "must be a non-blank string or a { ref, note } object")
  }
}

package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskRuntimeCheckpointIdentityVersionError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.goal.model.appendBoundedHistoryBySequence
import java.security.MessageDigest

/**
 * Durable append-only identity for every scoped checkpoint commit, structurally separate from
 * [FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY]. The two keys must never merge: phase records are
 * put()-replaced per phase id and hold only the latest output, while a checkpoint identity must
 * survive every later phase so a commit stays attributable to the boundary that created it.
 *
 * An absent key decodes to zero prior checkpoints, so a workflow created before this contract needs
 * no DDL migration.
 */
const val FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY: String =
  "feature_task_runtime_checkpoint_identities"

/** Mirrors the schema's `checkpoints.maxItems`; a schema-valid store can therefore never overflow it. */
const val FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_LIMIT: Int = 200

private const val OWNED_PATH_DIGEST_DELIMITER: Char = '\u0000'

/**
 * Reserved [FeatureTaskRuntimeCheckpointIdentity.subtaskId] for a feature-task run that is not a goal
 * continuation and therefore owns no decomposed subtask. A contract-level value: it appears in the
 * schema's `subtask_id` pattern and in every checkpoint ref a standalone run names.
 */
const val FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID: String = "standalone"

/**
 * The one ref namespace runtime checkpoint refs live in. Every ref write is confined to it, so nothing
 * in this ceremony can move or delete a branch ref.
 */
const val FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE: String = "refs/skill-bill/checkpoints"

private const val CHECKPOINT_REF_PREFIX: String = FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE

/**
 * The one place a checkpoint ref name is minted. Deterministic in its inputs so a resume that
 * re-reaches the checkpoint seam names the same ref and converges on the existing record instead of
 * appending a second one.
 */
fun featureTaskRuntimeCheckpointRefName(issueKey: String, subtaskId: String, sequenceNumber: Int): String =
  "$CHECKPOINT_REF_PREFIX/$issueKey/$subtaskId/$sequenceNumber"

/**
 * One checkpoint's identity. Effect-free: the application layer mints `sequenceNumber` and
 * `recordedAt` and passes them in, so this model carries no clock and no randomness.
 *
 * Every field is bounded and derived. The owned-path inventory is reduced to a digest and a count
 * rather than stored verbatim, which keeps the record within its durable bound while still proving
 * exactly which inventory the commit staged.
 */
data class FeatureTaskRuntimeCheckpointIdentity(
  val sequenceNumber: Int,
  val issueKey: String,
  val subtaskId: String,
  val checkpointRef: String,
  val branch: String,
  val phaseId: String,
  val generation: Int,
  val ownedPathDigest: String,
  val ownedPathCount: Int,
  val commitSha: String,
  val recordedAt: String,
  val loopId: String? = null,
  val parentSha: String? = null,
) {
  init {
    require(sequenceNumber >= 0) {
      "FeatureTaskRuntimeCheckpointIdentity.sequenceNumber must be non-negative, was $sequenceNumber."
    }
    require(issueKey.isNotBlank()) { "FeatureTaskRuntimeCheckpointIdentity.issueKey must be non-blank." }
    require(subtaskId.matches(SUBTASK_ID_PATTERN)) {
      "FeatureTaskRuntimeCheckpointIdentity.subtaskId must be a positive integer or " +
        "'$FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID', was '$subtaskId'."
    }
    require(checkpointRef.matches(CHECKPOINT_REF_PATTERN) && checkpointRef.length <= CHECKPOINT_REF_MAX_LENGTH) {
      "FeatureTaskRuntimeCheckpointIdentity.checkpointRef must be a bounded skill-bill checkpoint ref."
    }
    require(checkpointRef == featureTaskRuntimeCheckpointRefName(issueKey, subtaskId, sequenceNumber)) {
      "FeatureTaskRuntimeCheckpointIdentity.checkpointRef '$checkpointRef' does not derive from issueKey " +
        "'$issueKey', subtaskId '$subtaskId' and sequenceNumber $sequenceNumber; the ref is the identity, so a " +
        "ref naming a different authority boundary than its own record is rejected."
    }
    require(branch.isNotBlank()) { "FeatureTaskRuntimeCheckpointIdentity.branch must be non-blank." }
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimeCheckpointIdentity.phaseId must be non-blank." }
    require(generation >= 0) {
      "FeatureTaskRuntimeCheckpointIdentity.generation must be non-negative, was $generation."
    }
    require(ownedPathCount >= 0) {
      "FeatureTaskRuntimeCheckpointIdentity.ownedPathCount must be non-negative, was $ownedPathCount."
    }
    require(ownedPathDigest.matches(DIGEST_PATTERN)) {
      "FeatureTaskRuntimeCheckpointIdentity.ownedPathDigest must be a lowercase SHA-256 hex digest."
    }
    require(commitSha.matches(SHA_PATTERN)) {
      "FeatureTaskRuntimeCheckpointIdentity.commitSha must be a lowercase commit sha."
    }
    require(recordedAt.isNotBlank()) { "FeatureTaskRuntimeCheckpointIdentity.recordedAt must be non-blank." }
    parentSha?.let { sha ->
      require(sha.matches(SHA_PATTERN)) {
        "FeatureTaskRuntimeCheckpointIdentity.parentSha must be a lowercase commit sha when present."
      }
    }
    loopId?.let { id -> require(id.isNotBlank()) { "FeatureTaskRuntimeCheckpointIdentity.loopId must be non-blank." } }
  }

  @OpenBoundaryMap("Feature-task-runtime checkpoint-identity entry at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "sequence_number" to sequenceNumber,
    "issue_key" to issueKey,
    "subtask_id" to subtaskId,
    "checkpoint_ref" to checkpointRef,
    "branch" to branch,
    "phase_id" to phaseId,
    "generation" to generation,
    "owned_path_digest" to ownedPathDigest,
    "owned_path_count" to ownedPathCount,
    "commit_sha" to commitSha,
    "recorded_at" to recordedAt,
  ).apply {
    loopId?.let { put("loop_id", it) }
    parentSha?.let { put("parent_sha", it) }
  }

  companion object {
    private val DIGEST_PATTERN = Regex("^[0-9a-f]{64}$")
    private val SHA_PATTERN = Regex("^[0-9a-f]{40,64}$")
    private val SUBTASK_ID_PATTERN = Regex("^([0-9]+|$FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID)$")
    private val CHECKPOINT_REF_PATTERN =
      Regex("^$CHECKPOINT_REF_PREFIX/[A-Z][A-Z0-9]*-[0-9]+/[a-z0-9-]+/[0-9]+$")
    private const val CHECKPOINT_REF_MAX_LENGTH: Int = 255

    private val ALLOWED_FIELDS = setOf(
      "sequence_number",
      "issue_key",
      "subtask_id",
      "checkpoint_ref",
      "branch",
      "phase_id",
      "generation",
      "owned_path_digest",
      "owned_path_count",
      "commit_sha",
      "recorded_at",
      "loop_id",
      "parent_sha",
    )

    /** Strict decode; loud-fails on a missing or malformed field and never best-effort fills a default. */
    @OpenBoundaryMap("Feature-task-runtime checkpoint-identity decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeCheckpointIdentity {
      val unexpected = raw.keys - ALLOWED_FIELDS
      if (unexpected.isNotEmpty()) {
        checkpointIdentityError(
          "Feature-task-runtime checkpoint-identity entry carries unsupported fields " +
            "${unexpected.sorted()}; the store is quarantined and regenerated rather than reinterpreted.",
        )
      }
      return try {
        FeatureTaskRuntimeCheckpointIdentity(
          sequenceNumber = raw.requireIntField("sequence_number"),
          issueKey = raw.requireStringField("issue_key"),
          subtaskId = raw.requireStringField("subtask_id"),
          checkpointRef = raw.requireStringField("checkpoint_ref"),
          branch = raw.requireStringField("branch"),
          phaseId = raw.requireStringField("phase_id"),
          generation = raw.requireIntField("generation"),
          ownedPathDigest = raw.requireStringField("owned_path_digest"),
          ownedPathCount = raw.requireIntField("owned_path_count"),
          commitSha = raw.requireStringField("commit_sha"),
          recordedAt = raw.requireStringField("recorded_at"),
          loopId = raw.optionalStringField("loop_id"),
          parentSha = raw.optionalStringField("parent_sha"),
        )
      } catch (error: IllegalArgumentException) {
        checkpointIdentityError(
          "Feature-task-runtime checkpoint-identity entry is malformed: ${error.message}",
        )
      }
    }
  }
}

/**
 * Digest over the inventory a checkpoint staged. Sorting first makes the digest independent of the
 * order git listed the paths in. Each entry is length-prefixed rather than only delimiter-joined, so
 * a path that itself contains the delimiter cannot forge the digest of a different inventory.
 */
fun featureTaskRuntimeOwnedPathDigest(ownedPaths: List<String>): String {
  val normalized = ownedPaths.filter(String::isNotBlank).distinct().sorted()
  val digest = MessageDigest.getInstance("SHA-256")
  val framed = normalized.joinToString(OWNED_PATH_DIGEST_DELIMITER.toString()) { path ->
    "${path.length}:$path"
  }
  digest.update(framed.toByteArray())
  return digest.digest().joinToString("") { "%02x".format(it) }
}

@OpenBoundaryMap("Feature-task-runtime checkpoint-identity store at the durable workflow-artifact seam")
fun featureTaskRuntimeCheckpointIdentitiesToArtifact(
  identities: List<FeatureTaskRuntimeCheckpointIdentity>,
): Map<String, Any?> = linkedMapOf(
  "contract_version" to FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION,
  "checkpoints" to identities.map { it.toArtifactMap() },
)

/**
 * Strict decode of the whole store. An absent artifact decodes to an empty history; a record at an
 * unsupported contract version loud-fails so the caller quarantines and regenerates it instead of
 * reinterpreting a shape this version does not understand.
 */
@OpenBoundaryMap("Feature-task-runtime checkpoint-identity store decode from the durable workflow-artifact map")
fun featureTaskRuntimeCheckpointIdentitiesFromArtifact(raw: Any?): List<FeatureTaskRuntimeCheckpointIdentity> {
  if (raw == null) return emptyList()
  val map = JsonSupport.anyToStringAnyMap(raw)
    ?: checkpointIdentityError("Feature-task-runtime checkpoint-identity record must be an object.")
  val version = map["contract_version"] as? String
  if (version != FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION) {
    throw InvalidFeatureTaskRuntimeCheckpointIdentityVersionError(
      expectedContractVersion = FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION,
      actualContractVersion = version.orEmpty(),
    )
  }
  val checkpoints = map["checkpoints"] as? List<*>
    ?: checkpointIdentityError(
      "Feature-task-runtime checkpoint-identity record must carry a 'checkpoints' array.",
    )
  val decoded = checkpoints.map { entry ->
    FeatureTaskRuntimeCheckpointIdentity.fromArtifactMap(
      JsonSupport.anyToStringAnyMap(entry)
        ?: checkpointIdentityError("Feature-task-runtime checkpoint-identity entry must be an object."),
    )
  }
  val duplicateRefs = decoded.groupBy { it.checkpointRef }.filterValues { it.size > 1 }.keys
  if (duplicateRefs.isNotEmpty()) {
    checkpointIdentityError(
      "Feature-task-runtime checkpoint-identity history records checkpoint ref(s) ${duplicateRefs.sorted()} " +
        "more than once; one checkpoint ref yields exactly one identity record.",
    )
  }
  return decoded
}

/**
 * Appends one checkpoint identity and prunes oldest-first to
 * [FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_LIMIT]. Re-recording an already-recorded checkpoint ref
 * is a no-op rather than a duplicate: a resume that reaches this seam again after a crash between the
 * commit and the durable write must converge on the same single record. Dedupe keys on the ref, not
 * the sha — after an amend a later checkpoint legitimately points at an already-recorded sha, and
 * keying on the sha would silently swallow it.
 */
fun featureTaskRuntimeAppendCheckpointIdentity(
  existing: List<FeatureTaskRuntimeCheckpointIdentity>,
  entry: FeatureTaskRuntimeCheckpointIdentity,
  retentionLimit: Int = FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_LIMIT,
): List<FeatureTaskRuntimeCheckpointIdentity> {
  if (existing.any { it.checkpointRef == entry.checkpointRef }) return existing
  return appendBoundedHistoryBySequence(
    existing = existing.map { it.toArtifactMap() },
    entry = entry.toArtifactMap(),
    retentionLimit = retentionLimit,
  ).map(FeatureTaskRuntimeCheckpointIdentity::fromArtifactMap)
}

private fun checkpointIdentityError(detail: String): Nothing = throw InvalidWorkflowStateSchemaError(detail)

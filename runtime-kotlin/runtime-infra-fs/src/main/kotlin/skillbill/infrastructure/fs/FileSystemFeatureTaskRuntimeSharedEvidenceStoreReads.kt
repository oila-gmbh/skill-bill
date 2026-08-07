package skillbill.infrastructure.fs

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_SHARED_EVIDENCE_PROJECTION_CONTRACT_VERSION
import skillbill.contracts.workflow.FeatureTaskRuntimeSharedEvidenceProjectionSchemaValidator
import skillbill.error.InvalidFeatureTaskRuntimeSharedEvidenceProjectionSchemaError
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolution
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceDiffPayloadRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceFileEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceHunkEntry
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Returns the stored artifact only on a clean fingerprint hit whose projection passes schema
 * validation. Anything missing, unparseable, truncated, or schema-invalid returns null so the
 * caller re-derives; only a well-formed envelope that contradicts its own address throws.
 */
internal fun readStored(
  mapper: ObjectMapper,
  artifactDir: Path,
  fingerprint: String,
  workflowId: String,
  storePath: String,
): FeatureTaskRuntimeSharedEvidenceResolution? {
  val envelopePath = artifactDir.resolve(SHARED_EVIDENCE_ENVELOPE_FILE)
  val envelopeLabel = envelopePath.toString()
  val envelope = readEnvelope(mapper, envelopePath) ?: return null
  val recorded = recordedFingerprint(envelope, envelopeLabel, fingerprint) ?: return null
  return intactPayloadRef(artifactDir, envelope, envelopeLabel)?.let { payloadRef ->
    readPayloadText(artifactDir.resolve(payloadRef.relativePath))?.let { payloadText ->
      resolutionOf(
        StoredEnvelopePayload(envelope, recorded, payloadRef, payloadText),
        StoredReadContext(envelopeLabel, workflowId, storePath),
      )
    }
  }
}

/** The envelope's own fingerprint, or null once a blank one has been recorded as a corrupt entry. */
private fun recordedFingerprint(envelope: ObjectNode, envelopeLabel: String, addressed: String): String? {
  val recorded = envelope.path("fingerprint").asText("")
  if (recorded.isBlank()) {
    return degraded("stored_envelope_fingerprint", "re-derive", addressed, "blank at $envelopeLabel")
  }
  if (recorded != addressed) {
    throw FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError(
      addressedFingerprint = addressed,
      recordedFingerprint = recorded,
      sourceLabel = envelopeLabel,
    )
  }
  return recorded
}

private data class StoredEnvelopePayload(
  val envelope: ObjectNode,
  val recorded: String,
  val payloadRef: FeatureTaskRuntimeSharedEvidenceDiffPayloadRef,
  val payloadText: String,
)

private data class StoredReadContext(
  val envelopeLabel: String,
  val workflowId: String,
  val storePath: String,
)

private fun resolutionOf(
  stored: StoredEnvelopePayload,
  context: StoredReadContext,
): FeatureTaskRuntimeSharedEvidenceResolution? = try {
  val files = stored.envelope.path("files").map {
    FeatureTaskRuntimeSharedEvidenceFileEntry(it.path("path").asText(""), it.path("change_kind").asText(""))
  }
  val hunks = stored.envelope.path("hunks").map {
    FeatureTaskRuntimeSharedEvidenceHunkEntry(it.path("path").asText(""), it.path("header").asText(""))
  }
  val baseRef = stored.envelope.path("base_ref").takeIf { !it.isNull && !it.isMissingNode }?.asText()
  val headRef = stored.envelope.path("head_ref").takeIf { !it.isNull && !it.isMissingNode }?.asText()
  val contractVersion = stored.envelope.path("contract_version").asText("").ifBlank {
    FEATURE_TASK_RUNTIME_SHARED_EVIDENCE_PROJECTION_CONTRACT_VERSION
  }
  val projection = linkedMapOf<String, Any?>(
    "contract_version" to contractVersion,
    "workflow_id" to context.workflowId,
    "repository_checkpoint_fingerprint" to stored.recorded,
    "store_path" to context.storePath,
    "file_hunk_index" to files.map { file ->
      val hunkCount = hunks.count { it.path == file.path }
      "${file.changeKind} ${file.path} hunks=$hunkCount"
    },
  ).apply {
    baseRef?.takeIf { it.isNotBlank() }?.let { put("base_ref", it) }
    headRef?.takeIf { it.isNotBlank() }?.let { put("head_ref", it) }
    // A stored payload that inlined diff content is schema-invalid: never serve it.
    if (stored.envelope.has("diff_content") || stored.envelope.has("diff_bytes")) {
      put("diff_content", stored.envelope.path("diff_content").asText("present"))
    }
  }
  try {
    FeatureTaskRuntimeSharedEvidenceProjectionSchemaValidator.validate(projection, context.envelopeLabel)
  } catch (error: InvalidFeatureTaskRuntimeSharedEvidenceProjectionSchemaError) {
    return degraded(
      seam = "stored_projection_schema",
      used = "re-derive",
      expected = "schema-valid shared evidence projection at ${context.envelopeLabel}",
      cause = error.reason,
    )
  }
  FeatureTaskRuntimeSharedEvidenceResolution(
    artifact = FeatureTaskRuntimeSharedEvidenceArtifact(
      fingerprint = stored.recorded,
      baseRef = baseRef,
      headRef = headRef,
      files = files,
      hunks = hunks,
      diffPayload = stored.payloadRef,
    ),
    diffPayload = stored.payloadText,
  )
} catch (error: IllegalArgumentException) {
  // A well-formed envelope carrying blank index entries is still a corrupt cache entry.
  degraded(
    seam = "stored_envelope_index",
    used = "re-derive",
    expected = "non-blank file and hunk entries at ${context.envelopeLabel}",
    cause = "IllegalArgumentException: ${error.message.orEmpty()}",
  )
}

/** The stored payload ref, or null once the defect that makes it unservable has been recorded. */
private fun intactPayloadRef(
  artifactDir: Path,
  envelope: ObjectNode,
  envelopeLabel: String,
): FeatureTaskRuntimeSharedEvidenceDiffPayloadRef? {
  val relativePath = envelope.path("diff_payload").path("relative_path").asText("")
  if (relativePath.isBlank()) {
    return degraded(
      "stored_payload_relative_path",
      "re-derive",
      SHARED_EVIDENCE_PAYLOAD_FILE,
      "blank at $envelopeLabel",
    )
  }
  val expectedSize = envelope.path("diff_payload").path("size_bytes").asLong(-1)
  val payload = artifactDir.resolve(relativePath)
  val actualSize = readableSize(payload) ?: return null
  if (expectedSize != actualSize) {
    return degraded("stored_payload_size", "re-derive", "$expectedSize bytes", "truncated to $actualSize bytes")
  }
  return FeatureTaskRuntimeSharedEvidenceDiffPayloadRef(relativePath, actualSize)
}

/** An unreadable payload is a corrupt cache entry like any other: record it and re-derive. */
private fun readPayloadText(payload: Path): String? = try {
  Files.readString(payload)
} catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
  degraded(
    seam = "stored_payload_read",
    used = "re-derive",
    expected = "readable payload at $payload",
    cause = "${error::class.simpleName.orEmpty()}: ${error.message.orEmpty()}",
  )
}

private fun readableSize(payload: Path): Long? = try {
  if (Files.isRegularFile(payload)) {
    Files.size(payload)
  } else {
    degraded("stored_payload_file", "re-derive", "regular file at $payload", "absent or not a regular file")
  }
} catch (error: IOException) {
  degraded(
    seam = "stored_payload_size",
    used = "re-derive",
    expected = "readable size of $payload",
    cause = "${error::class.simpleName.orEmpty()}: ${error.message.orEmpty()}",
  )
}

private fun readEnvelope(mapper: ObjectMapper, path: Path): ObjectNode? {
  if (!Files.isRegularFile(path)) {
    return degraded("stored_envelope_file", "re-derive", "regular file at $path", "absent or not a regular file")
  }
  return try {
    mapper.readTree(Files.readString(path)) as? ObjectNode
      ?: degraded("stored_envelope_parse", "re-derive", "JSON object at $path", "parsed to a non-object node")
  } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
    // Unreadable or unparseable: the cache misses, the run continues, the record explains why.
    degraded(
      seam = "stored_envelope_parse",
      used = "re-derive",
      expected = "JSON object at $path",
      cause = "${error::class.simpleName.orEmpty()}: ${error.message.orEmpty()}",
    )
  }
}

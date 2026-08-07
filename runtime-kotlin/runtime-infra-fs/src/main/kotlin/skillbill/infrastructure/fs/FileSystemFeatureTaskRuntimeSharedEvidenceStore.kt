package skillbill.infrastructure.fs

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import me.tatarka.inject.annotations.Inject
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceDeriver
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceDerivation
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceRequest
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolution
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceDiffPayloadRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceFileEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceHunkEntry
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.logging.Logger

internal val sharedEvidenceStoreLog: Logger =
  Logger.getLogger("skillbill.infrastructure.fs.FileSystemFeatureTaskRuntimeSharedEvidenceStore")

/**
 * Emits the degradation record every cache-miss fallback in this store owes the observability
 * policy: the seam that degraded, the value actually used, the value expected, and the cause.
 * Returns null so a swallow site reads as `return degraded(...)` rather than a bare `return null`.
 */
private fun degraded(seam: String, used: String, expected: String, cause: String): Nothing? {
  sharedEvidenceStoreLog.warning(
    "shared review evidence cache degraded: seam=$seam used=$used expected=$expected cause=$cause",
  )
  return null
}

/**
 * Repo-local filesystem store for shared review evidence, addressed at
 * `<repoRoot>/.skill-bill/run-evidence/<workflowId>/<fingerprint>/`.
 *
 * The store lives beneath the repo-local `.skill-bill/` directory the install-time anchored
 * ignore rule already covers, so it adds no `.gitignore` entry of its own.
 */
@Inject
open class FileSystemFeatureTaskRuntimeSharedEvidenceStore : FeatureTaskRuntimeSharedEvidenceResolverPort {
  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  override fun resolve(
    request: FeatureTaskRuntimeSharedEvidenceRequest,
    deriver: FeatureTaskRuntimeSharedEvidenceDeriver,
  ): FeatureTaskRuntimeSharedEvidenceResolution {
    val fingerprint = request.checkpoint.fingerprint
    val artifactDir = artifactDir(request)
    readStored(mapper, artifactDir, fingerprint)?.let { return it }
    return persist(artifactDir, fingerprint, deriver.derive(request.checkpoint))
  }

  /**
   * Stages the envelope and the payload in a sibling directory and publishes them with a single
   * directory move, so an interrupted write can never leave a half-written artifact at the address
   * a later resolve reads.
   */
  private fun persist(
    artifactDir: Path,
    fingerprint: String,
    derivation: FeatureTaskRuntimeSharedEvidenceDerivation,
  ): FeatureTaskRuntimeSharedEvidenceResolution {
    val payloadBytes = derivation.diffPayload.toByteArray()
    val artifact = FeatureTaskRuntimeSharedEvidenceArtifact(
      fingerprint = fingerprint,
      baseRef = derivation.baseRef,
      headRef = derivation.headRef,
      files = derivation.files,
      hunks = derivation.hunks,
      diffPayload = FeatureTaskRuntimeSharedEvidenceDiffPayloadRef(PAYLOAD_FILE_NAME, payloadBytes.size.toLong()),
    )
    Files.createDirectories(artifactDir.parent)
    val staging = Files.createTempDirectory(artifactDir.parent, "${artifactDir.fileName}$STAGING_SUFFIX")
    try {
      writeStaged(staging, payloadBytes, mapper.writeValueAsString(envelopeOf(artifact)))
      publish(staging, artifactDir)
    } finally {
      deleteRecursively(staging)
    }
    return FeatureTaskRuntimeSharedEvidenceResolution(artifact, derivation.diffPayload)
  }

  /**
   * Open so a test can fail between the two staged writes; production behaviour is the two writes.
   * The atomicity claim is only observable when a failure lands mid-write, which no in-process
   * caller can otherwise provoke.
   */
  internal open fun writeStaged(staging: Path, payloadBytes: ByteArray, envelopeJson: String) {
    Files.write(staging.resolve(PAYLOAD_FILE_NAME), payloadBytes)
    Files.writeString(staging.resolve(ENVELOPE_FILE_NAME), envelopeJson)
  }

  private fun publish(staging: Path, artifactDir: Path) {
    try {
      Files.move(staging, artifactDir, ATOMIC_MOVE)
    } catch (error: AtomicMoveNotSupportedException) {
      degraded(
        seam = "artifact_publish",
        used = "non-atomic move to $artifactDir",
        expected = "ATOMIC_MOVE of $staging",
        cause = "AtomicMoveNotSupportedException: ${error.message.orEmpty()}",
      )
      deleteRecursively(artifactDir)
      Files.move(staging, artifactDir)
    } catch (error: FileAlreadyExistsException) {
      // A concurrent resolve published the same fingerprint first; its artifact is equivalent.
      degraded(
        seam = "artifact_publish",
        used = "the artifact already published at $artifactDir",
        expected = "publish of $staging",
        cause = "FileAlreadyExistsException: ${error.message.orEmpty()}",
      )
    } catch (error: FileSystemException) {
      // An artifact already occupies the address. Linux reports this as a bare FileSystemException
      // ("Directory not empty") rather than DirectoryNotEmptyException, so the branch is widened.
      degraded(
        seam = "artifact_publish",
        used = "replacement of the existing directory at $artifactDir",
        expected = "ATOMIC_MOVE of $staging",
        cause = "${error::class.simpleName.orEmpty()}: ${error.message.orEmpty()}",
      )
      deleteRecursively(artifactDir)
      Files.move(staging, artifactDir)
    }
  }

  private fun envelopeOf(artifact: FeatureTaskRuntimeSharedEvidenceArtifact): Map<String, Any?> = linkedMapOf(
    "fingerprint" to artifact.fingerprint,
    "base_ref" to artifact.baseRef,
    "head_ref" to artifact.headRef,
    "files" to artifact.files.map { mapOf("path" to it.path, "change_kind" to it.changeKind) },
    "hunks" to artifact.hunks.map { mapOf("path" to it.path, "header" to it.header) },
    "diff_payload" to mapOf(
      "relative_path" to artifact.diffPayload.relativePath,
      "size_bytes" to artifact.diffPayload.sizeBytes,
    ),
  )

  private fun deleteRecursively(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { stream ->
      stream.toList().sortedDescending().forEach { Files.deleteIfExists(it) }
    }
  }

  internal companion object {
    const val ENVELOPE_FILE_NAME: String = ENVELOPE_FILE
    const val PAYLOAD_FILE_NAME: String = PAYLOAD_FILE
    private const val STAGING_SUFFIX: String = ".staging."
  }
}

private const val ENVELOPE_FILE: String = "evidence.json"
private const val PAYLOAD_FILE: String = "diff.patch"

/**
 * Returns the stored artifact only on a clean fingerprint hit. Anything missing, unparseable, or
 * truncated returns null so the caller re-derives; only a well-formed envelope that contradicts
 * its own address throws.
 */
private fun readStored(
  mapper: ObjectMapper,
  artifactDir: Path,
  fingerprint: String,
): FeatureTaskRuntimeSharedEvidenceResolution? {
  val envelopePath = artifactDir.resolve(ENVELOPE_FILE)
  val envelopeLabel = envelopePath.toString()
  val envelope = readEnvelope(mapper, envelopePath) ?: return null
  val recorded = recordedFingerprint(envelope, envelopeLabel, fingerprint) ?: return null
  return intactPayloadRef(artifactDir, envelope, envelopeLabel)?.let { payloadRef ->
    readPayloadText(artifactDir.resolve(payloadRef.relativePath))?.let { payloadText ->
      resolutionOf(envelope, recorded, payloadRef, payloadText, envelopeLabel)
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

private fun resolutionOf(
  envelope: ObjectNode,
  recorded: String,
  payloadRef: FeatureTaskRuntimeSharedEvidenceDiffPayloadRef,
  payloadText: String,
  envelopeLabel: String,
): FeatureTaskRuntimeSharedEvidenceResolution? = try {
  FeatureTaskRuntimeSharedEvidenceResolution(
    artifact = FeatureTaskRuntimeSharedEvidenceArtifact(
      fingerprint = recorded,
      baseRef = envelope.path("base_ref").takeIf { !it.isNull && !it.isMissingNode }?.asText(),
      headRef = envelope.path("head_ref").takeIf { !it.isNull && !it.isMissingNode }?.asText(),
      files = envelope.path("files").map {
        FeatureTaskRuntimeSharedEvidenceFileEntry(it.path("path").asText(""), it.path("change_kind").asText(""))
      },
      hunks = envelope.path("hunks").map {
        FeatureTaskRuntimeSharedEvidenceHunkEntry(it.path("path").asText(""), it.path("header").asText(""))
      },
      diffPayload = payloadRef,
    ),
    diffPayload = payloadText,
  )
} catch (error: IllegalArgumentException) {
  // A well-formed envelope carrying blank index entries is still a corrupt cache entry.
  degraded(
    seam = "stored_envelope_index",
    used = "re-derive",
    expected = "non-blank file and hunk entries at $envelopeLabel",
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
    return degraded("stored_payload_relative_path", "re-derive", PAYLOAD_FILE, "blank at $envelopeLabel")
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

/**
 * Resolves the artifact directory from the repo root, mirroring the repoRoot-relative convention of
 * [configPath] rather than the userHome convention other adapters in this module use. Both address
 * segments are sanitized to a single path element so neither can escape the store root.
 */
internal fun artifactDir(request: FeatureTaskRuntimeSharedEvidenceRequest): Path = request.repoRoot
  .resolve(".skill-bill")
  .resolve("run-evidence")
  .resolve(pathSegment(request.workflowId))
  .resolve(pathSegment(request.checkpoint.fingerprint))
  .toAbsolutePath()
  .normalize()

private const val SAFE_SEGMENT_PUNCTUATION: String = "._-"

private fun pathSegment(raw: String): String {
  val sanitized = raw.map { char ->
    if (char.isLetterOrDigit() || char in SAFE_SEGMENT_PUNCTUATION) char else '_'
  }.joinToString("")
  return if (sanitized.isBlank() || sanitized.all { it == '.' }) "_$sanitized" else sanitized
}

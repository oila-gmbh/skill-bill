package skillbill.infrastructure.fs

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import me.tatarka.inject.annotations.Inject
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceDeriver
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceDerivation
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceRequest
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceDiffPayloadRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceFileEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceHunkEntry
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/**
 * Repo-local filesystem store for shared review evidence, addressed at
 * `<repoRoot>/.skill-bill/run-evidence/<workflowId>/<fingerprint>/`.
 *
 * The store lives beneath the repo-local `.skill-bill/` directory the install-time anchored
 * ignore rule already covers, so it adds no `.gitignore` entry of its own.
 */
@Inject
class FileSystemFeatureTaskRuntimeSharedEvidenceStore : FeatureTaskRuntimeSharedEvidenceResolverPort {
  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  override fun resolve(
    request: FeatureTaskRuntimeSharedEvidenceRequest,
    deriver: FeatureTaskRuntimeSharedEvidenceDeriver,
  ): FeatureTaskRuntimeSharedEvidenceArtifact {
    val fingerprint = request.checkpoint.fingerprint
    val artifactDir = artifactDir(request)
    readStored(artifactDir, fingerprint)?.let { return it }
    return persist(artifactDir, fingerprint, deriver.derive(request.checkpoint))
  }

  /**
   * Returns the stored artifact only on a clean fingerprint hit. Anything missing, unparseable, or
   * truncated returns null so the caller re-derives; only a well-formed envelope that contradicts
   * its own address throws.
   */
  private fun readStored(artifactDir: Path, fingerprint: String): FeatureTaskRuntimeSharedEvidenceArtifact? {
    val envelope = readEnvelope(artifactDir.resolve(ENVELOPE_FILE_NAME)) ?: return null
    val recorded = envelope.path("fingerprint").asText("")
    if (recorded != fingerprint) {
      throw FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError(
        addressedFingerprint = fingerprint,
        recordedFingerprint = recorded.ifBlank { "<absent>" },
        sourceLabel = artifactDir.resolve(ENVELOPE_FILE_NAME).toString(),
      )
    }
    val payloadRelativePath = envelope.path("diff_payload").path("relative_path").asText("")
    if (payloadRelativePath.isBlank()) return null
    val payload = artifactDir.resolve(payloadRelativePath)
    val expectedSize = envelope.path("diff_payload").path("size_bytes").asLong(-1)
    val actualSize = try {
      if (Files.isRegularFile(payload)) Files.size(payload) else return null
    } catch (@Suppress("SwallowedException") error: IOException) {
      return null
    }
    if (expectedSize != actualSize) return null
    return try {
      FeatureTaskRuntimeSharedEvidenceArtifact(
        fingerprint = recorded,
        baseRef = envelope.path("base_ref").takeIf { !it.isNull && !it.isMissingNode }?.asText(),
        headRef = envelope.path("head_ref").takeIf { !it.isNull && !it.isMissingNode }?.asText(),
        files = envelope.path("files").map {
          FeatureTaskRuntimeSharedEvidenceFileEntry(it.path("path").asText(""), it.path("change_kind").asText(""))
        },
        hunks = envelope.path("hunks").map {
          FeatureTaskRuntimeSharedEvidenceHunkEntry(it.path("path").asText(""), it.path("header").asText(""))
        },
        diffPayload = FeatureTaskRuntimeSharedEvidenceDiffPayloadRef(payloadRelativePath, actualSize),
      )
    } catch (@Suppress("SwallowedException") error: IllegalArgumentException) {
      // A well-formed envelope carrying blank index entries is still a corrupt cache entry.
      null
    }
  }

  private fun readEnvelope(path: Path): ObjectNode? = try {
    if (!Files.isRegularFile(path)) null else mapper.readTree(Files.readString(path)) as? ObjectNode
  } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception) {
    // Absent, unreadable, or unparseable: the cache misses, the run continues.
    null
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
  ): FeatureTaskRuntimeSharedEvidenceArtifact {
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
      Files.write(staging.resolve(PAYLOAD_FILE_NAME), payloadBytes)
      Files.writeString(staging.resolve(ENVELOPE_FILE_NAME), mapper.writeValueAsString(envelopeOf(artifact)))
      publish(staging, artifactDir)
    } finally {
      deleteRecursively(staging)
    }
    return artifact
  }

  private fun publish(staging: Path, artifactDir: Path) {
    try {
      Files.move(staging, artifactDir, ATOMIC_MOVE)
    } catch (@Suppress("SwallowedException") error: AtomicMoveNotSupportedException) {
      deleteRecursively(artifactDir)
      Files.move(staging, artifactDir)
    } catch (@Suppress("SwallowedException") error: FileAlreadyExistsException) {
      // A concurrent resolve published the same fingerprint first; its artifact is equivalent.
    } catch (@Suppress("SwallowedException") error: DirectoryNotEmptyException) {
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
    const val ENVELOPE_FILE_NAME: String = "evidence.json"
    const val PAYLOAD_FILE_NAME: String = "diff.patch"
    private const val STAGING_SUFFIX: String = ".staging."
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

private fun pathSegment(raw: String): String {
  val sanitized = raw.map { char ->
    if (char.isLetterOrDigit() || char == '.' || char == '_' || char == '-') char else '_'
  }.joinToString("")
  return if (sanitized.isBlank() || sanitized.all { it == '.' }) "_$sanitized" else sanitized
}

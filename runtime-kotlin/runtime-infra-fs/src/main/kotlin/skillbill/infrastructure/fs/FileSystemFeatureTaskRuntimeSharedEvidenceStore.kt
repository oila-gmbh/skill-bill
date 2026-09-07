package skillbill.infrastructure.fs

import com.fasterxml.jackson.databind.ObjectMapper
import me.tatarka.inject.annotations.Inject
import skillbill.error.ReviewHunkEvidenceLocatorMissingError
import skillbill.error.ReviewHunkEvidenceLocatorUnreadableError
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceDeriver
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceDerivation
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceLocatorReadRequest
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceRequest
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolution
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolveOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceDiffPayloadRef
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
internal fun degraded(seam: String, used: String, expected: String, cause: String): Nothing? {
  sharedEvidenceStoreLog.warning(
    "shared review evidence cache degraded: seam=$seam used=$used expected=$expected cause=$cause",
  )
  return null
}

internal const val SHARED_EVIDENCE_ENVELOPE_FILE: String = "evidence.json"
internal const val SHARED_EVIDENCE_PAYLOAD_FILE: String = "diff.patch"

/**
 * Repo-local filesystem store for shared review evidence, addressed at
 * `<repoRoot>/.skill-bill/run-evidence/<workflowId>/<fingerprint>/`.
 *
 * The store lives beneath the repo-local `.skill-bill/` directory the install-time anchored
 * ignore rule already covers, so it adds no `.gitignore` entry of its own.
 */
@Inject
open class FileSystemFeatureTaskRuntimeSharedEvidenceStore :
  FeatureTaskRuntimeSharedEvidenceResolverPort,
  FeatureTaskRuntimeSharedEvidenceLocatorReadPort {
  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  override fun resolve(
    request: FeatureTaskRuntimeSharedEvidenceRequest,
    deriver: FeatureTaskRuntimeSharedEvidenceDeriver,
  ): FeatureTaskRuntimeSharedEvidenceResolution {
    val fingerprint = request.checkpoint.fingerprint
    val artifactDir = artifactDir(request)
    val storePath = storePath(request.repoRoot, artifactDir)
    readStored(mapper, artifactDir, fingerprint, request.workflowId, storePath)?.let {
      return it.copy(storePath = storePath, outcome = FeatureTaskRuntimeSharedEvidenceResolveOutcome.REUSE)
    }
    val outcome = if (siblingFingerprintsExist(artifactDir)) {
      FeatureTaskRuntimeSharedEvidenceResolveOutcome.CHECKPOINT_CHANGE_REDERIVATION
    } else {
      FeatureTaskRuntimeSharedEvidenceResolveOutcome.DERIVATION
    }
    return persist(artifactDir, fingerprint, deriver.derive(request.checkpoint))
      .copy(storePath = storePath, outcome = outcome)
  }

  override fun readDiffPayload(request: FeatureTaskRuntimeSharedEvidenceLocatorReadRequest): String {
    val repoRoot = request.repoRoot.toAbsolutePath().normalize()
    val artifactDir = repoRoot.resolve(request.storePath).normalize()
    val storeRoot = repoRoot.resolve(".skill-bill").resolve("run-evidence").normalize()
    if (!artifactDir.startsWith(storeRoot) || !Files.isDirectory(artifactDir)) {
      throw ReviewHunkEvidenceLocatorMissingError(request.storePath)
    }
    val fingerprint = artifactDir.fileName.toString()
    val workflowId = artifactDir.parent.fileName.toString()
    val publishedPath = storePath(request.repoRoot, artifactDir)
    val stored = readStored(mapper, artifactDir, fingerprint, workflowId, publishedPath)
      ?: throw ReviewHunkEvidenceLocatorUnreadableError(
        request.storePath,
        "stored artifact is missing, truncated, or unreadable",
      )
    val payloadPath = artifactDir.resolve(request.payloadFile)
    if (!Files.isRegularFile(payloadPath)) {
      throw ReviewHunkEvidenceLocatorUnreadableError(request.storePath, "payload file is not a regular file")
    }
    return stored.diffPayload
  }

  /**
   * True when this workflow already holds at least one published fingerprint directory other than
   * the one about to be derived. That is the store-local signal that a miss is a checkpoint-change
   * re-derivation rather than the workflow's first derivation.
   */
  private fun siblingFingerprintsExist(artifactDir: Path): Boolean {
    val parent = artifactDir.parent ?: return false
    if (!Files.isDirectory(parent)) return false
    return Files.list(parent).use { stream ->
      stream.anyMatch { candidate ->
        candidate.fileName.toString() != artifactDir.fileName.toString() &&
          !candidate.fileName.toString().contains(STAGING_SUFFIX) &&
          Files.isDirectory(candidate)
      }
    }
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
      diffPayload = FeatureTaskRuntimeSharedEvidenceDiffPayloadRef(
        SHARED_EVIDENCE_PAYLOAD_FILE,
        payloadBytes.size.toLong(),
      ),
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
    Files.write(staging.resolve(SHARED_EVIDENCE_PAYLOAD_FILE), payloadBytes)
    Files.writeString(staging.resolve(SHARED_EVIDENCE_ENVELOPE_FILE), envelopeJson)
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
    const val ENVELOPE_FILE_NAME: String = SHARED_EVIDENCE_ENVELOPE_FILE
    const val PAYLOAD_FILE_NAME: String = SHARED_EVIDENCE_PAYLOAD_FILE
    private const val STAGING_SUFFIX: String = ".staging."
  }
}

/**
 * The published address: repo-relative, so it stays a short portable token in a delivered projection
 * rather than an absolute path that leaks the checkout location. Falls back to the absolute path when
 * the artifact dir is not under the repo root, which only a non-normalizable [repoRoot] can produce.
 */
internal fun storePath(repoRoot: Path, artifactDir: Path): String =
  runCatching { repoRoot.toAbsolutePath().normalize().relativize(artifactDir).toString() }
    .getOrNull()
    ?.takeIf { it.isNotBlank() && !it.startsWith("..") }
    ?: artifactDir.toString()

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

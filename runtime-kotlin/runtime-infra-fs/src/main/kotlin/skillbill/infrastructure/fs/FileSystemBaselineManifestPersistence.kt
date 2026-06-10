package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.install.model.BaselineManifest
import skillbill.ports.install.baseline.BaselineManifestPersistencePort
import skillbill.ports.install.baseline.model.ReadBaselineManifestRequest
import skillbill.ports.install.baseline.model.ReadBaselineManifestResult
import skillbill.ports.install.baseline.model.WriteBaselineManifestRequest
import skillbill.ports.install.baseline.model.WriteBaselineManifestResult
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * SKILL-76 Subtask 2: durable baseline manifest adapter. Mirrors
 * [FileSystemInstallSelectionPersistence] EXACTLY — resolves
 * `<home>/.skill-bill/baseline-manifest.json`, loud-fails typed read errors, and
 * writes atomically (temp file + ATOMIC_MOVE with a REPLACE_EXISTING fallback).
 *
 * Difference from install-selection: a MISSING baseline manifest is not an error
 * (first install / freshly copied source with no manifest yet). The read returns
 * an empty manifest with `existed = false` so the reconcile policy classifies
 * every skill as new-upstream on a fresh install.
 */
@Inject
class FileSystemBaselineManifestPersistence : BaselineManifestPersistencePort {
  override fun readBaseline(request: ReadBaselineManifestRequest): ReadBaselineManifestResult {
    val manifestPath = baselineManifestPath(request.installHome)
    if (!Files.exists(manifestPath)) {
      return ReadBaselineManifestResult(manifest = BaselineManifest.empty(), existed = false)
    }
    val size = baselineRecordSize(manifestPath)
    if (size > MAX_BASELINE_MANIFEST_BYTES) {
      throw unreadableBaseline(manifestPath, "Manifest exceeds the maximum size of $MAX_BASELINE_MANIFEST_BYTES bytes")
    }
    val manifest = parseBaselineManifestPayload(manifestPath, readBaselinePayload(manifestPath))
    return ReadBaselineManifestResult(manifest = manifest, existed = true)
  }

  override fun writeBaseline(request: WriteBaselineManifestRequest): WriteBaselineManifestResult {
    val manifestPath = baselineManifestPath(request.installHome)
    manifestPath.parent?.let(Files::createDirectories)
    val payload = request.manifest.toBaselineManifestJson()
    val durablePayload = payload + "\n"
    if (durablePayload.toByteArray(StandardCharsets.UTF_8).size > MAX_BASELINE_MANIFEST_BYTES) {
      throw unreadableBaseline(manifestPath, "Manifest exceeds the maximum size of $MAX_BASELINE_MANIFEST_BYTES bytes")
    }
    // Parse-on-write round-trip guard: a malformed render loud-fails before disk is touched.
    parseBaselineManifestPayload(manifestPath, payload)
    writeBaselineRecord(manifestPath, durablePayload)
    return WriteBaselineManifestResult(path = manifestPath)
  }
}

private fun writeBaselineRecord(path: Path, payload: String) {
  val parent = path.parent ?: path.toAbsolutePath().normalize().parent
  val tempFile = Files.createTempFile(parent, "${path.fileName}.", ".tmp")
  try {
    Files.writeString(tempFile, payload, StandardCharsets.UTF_8)
    try {
      Files.move(tempFile, path, ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(tempFile, path, REPLACE_EXISTING)
    }
  } finally {
    Files.deleteIfExists(tempFile)
  }
}

private fun baselineRecordSize(path: Path): Long = try {
  Files.size(path)
} catch (error: IOException) {
  throw unreadableBaseline(path, "size lookup failed", error)
} catch (error: SecurityException) {
  throw unreadableBaseline(path, "size lookup denied", error)
}

private fun readBaselinePayload(path: Path): String = try {
  Files.readString(path)
} catch (error: IOException) {
  throw unreadableBaseline(path, "read failed", error)
} catch (error: SecurityException) {
  throw unreadableBaseline(path, "read denied", error)
}

private fun baselineManifestPath(installHome: Path): Path = installHome
  .resolve(".skill-bill")
  .resolve(BASELINE_MANIFEST_FILE_NAME)
  .toAbsolutePath()
  .normalize()

private const val MAX_BASELINE_MANIFEST_BYTES = 1024 * 1024

package skillbill.ports.workflow

import skillbill.boundary.OpenBoundaryMap
import java.nio.file.Path

interface DecompositionManifestFileStore {
  fun readText(path: Path): String
  fun isRegularFile(path: Path): Boolean
  fun findDecompositionManifestFiles(repoRoot: Path): List<Path>
  fun listDirectChildDirectories(directory: Path): List<Path> = emptyList()
  fun writeTextAtomically(target: Path, content: String)
  fun deleteIfExists(target: Path)

  /**
   * Publishes a prepared text bundle and verifies it before returning. If verification or any
   * write fails, restore the exact pre-write contents so callers never observe a partial bundle.
   */
  fun <T> writeBundleAtomically(writes: List<Pair<Path, String>>, verify: () -> T): T {
    val snapshots = writes.distinctBy { (path, _) -> path }.map { (path, _) ->
      val existed = isRegularFile(path)
      DecompositionManifestFileSnapshot(path, existed, if (existed) readText(path) else null)
    }
    return runCatching {
      writes.forEach { (path, content) -> writeTextAtomically(path, content) }
      verify()
    }.getOrElse { failure ->
      snapshots.asReversed().forEach { snapshot ->
        runCatching {
          if (snapshot.existed) {
            writeTextAtomically(snapshot.path, requireNotNull(snapshot.content))
          } else {
            deleteIfExists(snapshot.path)
          }
        }.onFailure(failure::addSuppressed)
      }
      throw failure
    }
  }

  /**
   * Serializes a schema-validated decomposition-manifest wire map to YAML
   * text. The raw map is the canonical wire-shape envelope at the
   * infra/codec serialization seam — it mirrors the
   * [skillbill.workflow.DecompositionManifestValidator] decode seam, so the
   * concrete `YAMLMapper` mechanics stay in the infra-fs adapter.
   */
  @OpenBoundaryMap("Decomposition manifest wire map at the YAML serialization seam")
  fun encodeManifestYaml(wireMap: Map<String, Any?>): String
}

object UnavailableDecompositionManifestFileStore : DecompositionManifestFileStore {
  override fun readText(path: Path): String = unavailable()

  override fun isRegularFile(path: Path): Boolean = unavailable()

  override fun findDecompositionManifestFiles(repoRoot: Path): List<Path> = unavailable()

  override fun listDirectChildDirectories(directory: Path): List<Path> = unavailable()

  override fun writeTextAtomically(target: Path, content: String): Unit = unavailable()

  override fun deleteIfExists(target: Path): Unit = unavailable()

  override fun encodeManifestYaml(wireMap: Map<String, Any?>): String = unavailable()

  private fun unavailable(): Nothing {
    error("Decomposition manifest file store is not configured for this runtime.")
  }
}

private data class DecompositionManifestFileSnapshot(
  val path: Path,
  val existed: Boolean,
  val content: String?,
)

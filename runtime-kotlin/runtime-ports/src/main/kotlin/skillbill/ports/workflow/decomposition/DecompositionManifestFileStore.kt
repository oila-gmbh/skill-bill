package skillbill.ports.workflow.decomposition

import skillbill.boundary.OpenBoundaryMap
import java.nio.file.Path

interface DecompositionManifestFileReadStore {
  fun readText(path: Path): String
  fun readTextWithoutRecovery(path: Path): String = readText(path)
  fun isRegularFile(path: Path): Boolean
  fun isRegularFileWithoutRecovery(path: Path): Boolean = isRegularFile(path)
}

interface DecompositionManifestFileDiscoveryStore {
  fun findDecompositionManifestFiles(repoRoot: Path): List<Path>
  fun findDecompositionManifestFilesWithoutRecovery(repoRoot: Path): List<Path> =
    findDecompositionManifestFiles(repoRoot)
  fun listDirectChildDirectories(directory: Path): List<Path> = emptyList()
}

interface DecompositionManifestFileWriteStore {
  fun writeTextAtomically(target: Path, content: String)
  fun deleteIfExists(target: Path)
}

interface DecompositionManifestFileEncodeStore {
  @OpenBoundaryMap("Decomposition manifest wire map at the YAML serialization seam")
  fun encodeManifestYaml(wireMap: Map<String, Any?>): String
}

interface DecompositionManifestFileStore :
  DecompositionManifestFileReadStore,
  DecompositionManifestFileDiscoveryStore,
  DecompositionManifestFileWriteStore,
  DecompositionManifestFileEncodeStore

fun <T> DecompositionManifestFileStore.writeBundleAtomically(writes: List<Pair<Path, String>>, verify: () -> T): T {
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

private data class DecompositionManifestFileSnapshot(
  val path: Path,
  val existed: Boolean,
  val content: String?,
)

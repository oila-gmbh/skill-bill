package skillbill.ports.workflow.decomposition

import java.nio.file.Path

object UnavailableDecompositionManifestFileStore : DecompositionManifestFileStore {
  override fun readText(path: Path): String = unavailableDecompositionManifestFileStore()

  override fun readTextWithoutRecovery(path: Path): String = unavailableDecompositionManifestFileStore()

  override fun isRegularFile(path: Path): Boolean = unavailableDecompositionManifestFileStore()

  override fun isRegularFileWithoutRecovery(path: Path): Boolean = unavailableDecompositionManifestFileStore()

  override fun findDecompositionManifestFiles(repoRoot: Path): List<Path> = unavailableDecompositionManifestFileStore()

  override fun findDecompositionManifestFilesWithoutRecovery(repoRoot: Path): List<Path> =
    unavailableDecompositionManifestFileStore()

  override fun listDirectChildDirectories(directory: Path): List<Path> = unavailableDecompositionManifestFileStore()

  override fun writeTextAtomically(target: Path, content: String): Unit = unavailableDecompositionManifestFileStore()

  override fun deleteIfExists(target: Path): Unit = unavailableDecompositionManifestFileStore()

  override fun encodeManifestYaml(wireMap: Map<String, Any?>): String = unavailableDecompositionManifestFileStore()
}

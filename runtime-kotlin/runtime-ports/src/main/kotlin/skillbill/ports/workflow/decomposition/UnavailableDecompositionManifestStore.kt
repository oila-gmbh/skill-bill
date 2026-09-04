package skillbill.ports.workflow.decomposition

import java.nio.file.Path

object UnavailableDecompositionManifestStore : DecompositionManifestStore {
  override fun readText(path: Path): String = unavailableDecompositionManifestStore()

  override fun readTextWithoutRecovery(path: Path): String = unavailableDecompositionManifestStore()

  override fun isRegularFile(path: Path): Boolean = unavailableDecompositionManifestStore()

  override fun isRegularFileWithoutRecovery(path: Path): Boolean = unavailableDecompositionManifestStore()

  override fun findDecompositionManifestFiles(repoRoot: Path): List<Path> = unavailableDecompositionManifestStore()

  override fun findDecompositionManifestFilesWithoutRecovery(repoRoot: Path): List<Path> =
    unavailableDecompositionManifestStore()

  override fun listDirectChildDirectories(directory: Path): List<Path> = unavailableDecompositionManifestStore()

  override fun writeTextAtomically(target: Path, content: String): Unit = unavailableDecompositionManifestStore()

  override fun deleteIfExists(target: Path): Unit = unavailableDecompositionManifestStore()

  override fun encodeManifestYaml(wireMap: Map<String, Any?>): String = unavailableDecompositionManifestStore()
}

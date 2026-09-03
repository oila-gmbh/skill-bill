package skillbill.ports.workflow.decomposition

import java.nio.file.Path

interface DecompositionManifestDiscoveryPort {
  fun findDecompositionManifestFiles(repoRoot: Path): List<Path>
  fun findDecompositionManifestFilesWithoutRecovery(repoRoot: Path): List<Path> =
    findDecompositionManifestFiles(repoRoot)
  fun listDirectChildDirectories(directory: Path): List<Path>
}

interface DecompositionManifestStore :
  DecompositionManifestPersistencePort,
  DecompositionManifestDiscoveryPort

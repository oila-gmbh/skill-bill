package skillbill.application.decomposition

import skillbill.application.workflow.repoRoot
import java.nio.file.Path

internal const val DECOMPOSITION_MANIFEST_FILENAME: String = "decomposition-manifest.yaml"

internal fun decompositionManifestPath(repoRoot: Path, parentSpecPath: Path, subtaskSpecPaths: List<String>): Path =
  decompositionManifestDirectory(repoRoot, parentSpecPath, subtaskSpecPaths)
    .resolve(DECOMPOSITION_MANIFEST_FILENAME)

internal fun decompositionManifestDirectory(
  repoRoot: Path,
  parentSpecPath: Path,
  subtaskSpecPaths: List<String>,
): Path {
  val parentDirectory = resolvedParentSpecPath(repoRoot, parentSpecPath).parent
  val subtaskDirectories = subtaskSpecPaths
    .map { resolvedParentSpecPath(repoRoot, Path.of(it)).parent }
    .distinct()
  return subtaskDirectories.singleOrNull() ?: parentDirectory
}

package skillbill.application.decomposition

import java.nio.file.Path

const val DECOMPOSITION_MANIFEST_FILENAME: String = "decomposition-manifest.yaml"

fun decompositionManifestPath(repoRoot: Path, parentSpecPath: Path, subtaskSpecPaths: List<String>): Path =
  decompositionManifestDirectory(repoRoot, parentSpecPath, subtaskSpecPaths)
    .resolve(DECOMPOSITION_MANIFEST_FILENAME)

fun decompositionManifestDirectory(repoRoot: Path, parentSpecPath: Path, subtaskSpecPaths: List<String>): Path {
  val parentDirectory = resolvedParentSpecPath(repoRoot, parentSpecPath).parent
  val subtaskDirectories = subtaskSpecPaths
    .map { resolvedParentSpecPath(repoRoot, Path.of(it)).parent }
    .distinct()
  return subtaskDirectories.singleOrNull() ?: parentDirectory
}

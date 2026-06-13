package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.application.decomposition.loadManifestOrNull
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.application.workflow.repoRoot
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.SpecSourceSpecReader
import skillbill.workflow.model.SpecSource
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Additive, artifact-only resolver of the persisted `spec_source` stamp. Decomposed runs read it
 * from the sibling decomposition manifest; single_spec runs read it from the `spec.md` line. Config
 * is never consulted (AC5). Any benign absence resolves to [SpecSource.LOCAL] so local-mode stays
 * the untouched fast path (AC6). This read is non-mutating and must not touch run-invariant freezing
 * or spec_path resolution — it only reports what the artifact already declared.
 */
@Inject
class SpecSourceResolver(
  private val fileStore: DecompositionManifestFileStore,
  private val validator: DecompositionManifestValidator,
) {
  fun resolve(repoRoot: Path, specReference: String, isGoalContinuation: Boolean): SpecSource {
    val specPath = resolvedParentSpecPath(repoRoot, Path.of(specReference))
    val manifestPath = specPath.parent?.resolve(DECOMPOSITION_MANIFEST_FILENAME)
    if (manifestPath != null && (isGoalContinuation || fileStore.isRegularFile(manifestPath))) {
      loadManifestOrNull(manifestPath, validator, fileStore)?.let { return it.specSource }
    }
    return singleSpecSource(specPath)
  }

  private fun singleSpecSource(specPath: Path): SpecSource = try {
    if (fileStore.isRegularFile(specPath)) {
      SpecSourceSpecReader.parseSpecSource(fileStore.readText(specPath))
    } else {
      SpecSource.LOCAL
    }
  } catch (_: NoSuchFileException) {
    SpecSource.LOCAL
  }
}

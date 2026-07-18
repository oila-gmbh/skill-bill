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
 * Artifact-only resolver of the persisted `spec_source` stamp. Prepared runs read it from the
 * sibling decomposition manifest, which is the sole prepared-feature authority marker. A
 * manifest-absent runtime workflow may predate that invariant, so its already-persisted spec stamp
 * remains the compatibility fallback. Config is never consulted and benign absence resolves to
 * [SpecSource.LOCAL].
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
    return legacySpecSource(specPath)
  }

  private fun legacySpecSource(specPath: Path): SpecSource = try {
    if (fileStore.isRegularFile(specPath)) {
      SpecSourceSpecReader.parseSpecSource(fileStore.readText(specPath))
    } else {
      SpecSource.LOCAL
    }
  } catch (_: NoSuchFileException) {
    SpecSource.LOCAL
  }
}

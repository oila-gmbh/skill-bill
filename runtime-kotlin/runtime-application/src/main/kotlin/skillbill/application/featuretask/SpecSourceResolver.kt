package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.application.decomposition.loadManifestOrNull
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.application.workflow.repoRoot
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.model.SpecSource
import java.nio.file.Path

/**
 * Artifact-only resolver of the persisted `spec_source` stamp. Prepared runs read it from the
 * sibling decomposition manifest, which is the sole prepared-feature authority marker. Config and
 * parent-spec metadata are never consulted. Any benign absence resolves to [SpecSource.LOCAL] so
 * legacy nonterminal workflows without a manifest retain their existing local behavior.
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
    return SpecSource.LOCAL
  }
}

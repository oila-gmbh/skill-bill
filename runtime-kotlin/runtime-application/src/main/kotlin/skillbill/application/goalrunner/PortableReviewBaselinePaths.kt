package skillbill.application.goalrunner

import skillbill.workflow.decomposition.model.DecompositionManifest
import java.nio.file.Path

object PortableReviewBaselinePaths {
  fun artifactPath(manifest: DecompositionManifest, subtaskId: Int): Path {
    val parentSpec = Path.of(manifest.parentSpecPath)
    val featureDir = if (parentSpec.isAbsolute) {
      parentSpec.parent
    } else {
      Path.of(".feature-specs").resolve(featureDirName(manifest))
    }
    return featureDir.resolve(PORTABLE_BASELINE_DIR).resolve("subtask-$subtaskId.yaml")
  }

  fun artifactPath(repoRoot: Path, manifest: DecompositionManifest, subtaskId: Int): Path =
    repoRoot.resolve(artifactPath(manifest, subtaskId)).normalize()

  private fun featureDirName(manifest: DecompositionManifest): String =
    "${manifest.issueKey.trim().uppercase()}-${manifest.featureName}"

  private const val PORTABLE_BASELINE_DIR = "portable-review-baselines"
}

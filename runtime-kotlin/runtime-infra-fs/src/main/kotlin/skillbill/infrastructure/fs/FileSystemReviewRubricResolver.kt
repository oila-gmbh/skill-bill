package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.review.ReviewRubricResolver
import skillbill.ports.review.model.ResolvedReviewRubric
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files

@Inject
class FileSystemReviewRubricResolver : ReviewRubricResolver {
  override fun resolve(manifest: PlatformManifest?): ResolvedReviewRubric {
    if (manifest == null) return ResolvedReviewRubric(GENERIC_RUBRIC_ID, GENERIC_RUBRIC)
    val baseline = requireNotNull(manifest.declaredFiles.baseline) {
      "Platform pack '${manifest.slug}' does not declare a code-review baseline."
    }.toRealPath()
    val packRoot = manifest.packRoot.toRealPath()
    require(baseline.startsWith(packRoot) && Files.isRegularFile(baseline) && !Files.isSymbolicLink(baseline)) {
      "Platform pack '${manifest.slug}' declares an unreadable code-review baseline."
    }
    val size = Files.size(baseline)
    require(size <= MAX_RUBRIC_BYTES) {
      "Platform pack '${manifest.slug}' declares a code-review baseline larger than $MAX_RUBRIC_BYTES bytes."
    }
    return ResolvedReviewRubric(
      requireNotNull(manifest.routedSkillName),
      Files.readString(baseline),
    )
  }

  private companion object {
    const val MAX_RUBRIC_BYTES = 256 * 1024L
    const val GENERIC_RUBRIC_ID = "parallel-code-review"
    const val GENERIC_RUBRIC =
      "Find concrete correctness, security, reliability, performance, and test gaps in the assigned change."
  }
}

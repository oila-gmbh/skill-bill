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
    val specialists = manifest.declaredCodeReviewAreas.map { area ->
      val file = requireNotNull(manifest.declaredFiles.areas[area]) {
        "Platform pack '${manifest.slug}' does not declare its '$area' specialist file."
      }.toRealPath()
      require(file.startsWith(packRoot) && Files.isRegularFile(file) && !Files.isSymbolicLink(file)) {
        "Platform pack '${manifest.slug}' declares an unreadable '$area' code-review rubric."
      }
      val body = Files.readString(file)
      require(body.toByteArray().size <= MAX_RUBRIC_BYTES) {
        "Platform pack '${manifest.slug}' declares a '$area' rubric larger than $MAX_RUBRIC_BYTES bytes."
      }
      ResolvedReviewRubric(
        rubricId = "bill-${manifest.slug}-code-review-$area",
        body = body,
        area = area,
      )
    }
    val baselineBody = Files.readString(baseline)
    require(baselineBody.toByteArray().size <= MAX_RUBRIC_BYTES) {
      "Platform pack '${manifest.slug}' declares a code-review baseline larger than $MAX_RUBRIC_BYTES bytes."
    }
    return ResolvedReviewRubric(
      requireNotNull(manifest.routedSkillName),
      baselineBody,
      specialists = specialists,
    )
  }

  private companion object {
    const val MAX_RUBRIC_BYTES = 256 * 1024L
    const val GENERIC_RUBRIC_ID = "parallel-code-review"
    const val GENERIC_RUBRIC =
      "Find concrete correctness, security, reliability, performance, and test gaps in the assigned change."
  }
}

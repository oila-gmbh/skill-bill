package skillbill.ports.review

import skillbill.ports.review.model.ResolvedReviewRubric
import skillbill.scaffold.model.PlatformManifest

fun interface ReviewRubricResolver {
  fun resolve(manifest: PlatformManifest?): ResolvedReviewRubric

  fun resolve(
    manifest: PlatformManifest?,
    evidence: List<ReviewOwnedFileEvidence>,
    specialistSkillName: String,
  ): ResolvedReviewRubric =
    resolve(manifest)
}

data class ReviewOwnedFileEvidence(val path: String, val changedContent: String) {
  init {
    require(path.isNotBlank() && !path.startsWith('/'))
  }
}

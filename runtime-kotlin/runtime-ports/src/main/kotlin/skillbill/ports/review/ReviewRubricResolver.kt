package skillbill.ports.review

import skillbill.ports.review.model.ResolvedReviewRubric
import skillbill.ports.review.model.ReviewOwnedFileEvidence
import skillbill.scaffold.model.PlatformManifest

fun interface ReviewRubricResolver {
  fun resolve(manifest: PlatformManifest?): ResolvedReviewRubric

  fun resolve(
    manifest: PlatformManifest?,
    evidence: List<ReviewOwnedFileEvidence>,
    specialistSkillName: String,
  ): ResolvedReviewRubric = resolve(manifest)
}

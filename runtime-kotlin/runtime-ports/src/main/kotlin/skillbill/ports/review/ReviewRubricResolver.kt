package skillbill.ports.review

import skillbill.ports.review.model.ResolvedReviewRubric
import skillbill.scaffold.model.PlatformManifest

fun interface ReviewRubricResolver {
  fun resolve(manifest: PlatformManifest?): ResolvedReviewRubric

  fun resolve(manifest: PlatformManifest?, diff: String, specialistSkillName: String): ResolvedReviewRubric =
    resolve(manifest)
}

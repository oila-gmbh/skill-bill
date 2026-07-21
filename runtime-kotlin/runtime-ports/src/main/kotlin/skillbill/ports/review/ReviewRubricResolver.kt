package skillbill.ports.review

import skillbill.ports.review.model.ResolvedReviewRubric
import skillbill.scaffold.model.PlatformManifest

fun interface ReviewRubricResolver {
  fun resolve(manifest: PlatformManifest?): ResolvedReviewRubric
}

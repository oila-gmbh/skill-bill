package skillbill.review.plan

import skillbill.error.InvalidFallbackCapabilityError
import skillbill.scaffold.model.PlatformManifest

object ReviewFallbackResolver {
  fun resolveOptional(manifests: List<PlatformManifest>): PlatformManifest? {
    val owners = manifests.filter { CODE_REVIEW_CAPABILITY in it.fallbackCapabilities }
    if (owners.size > 1) {
      throw InvalidFallbackCapabilityError(
        "Code-review fallback has multiple manifest-declared owners: ${owners.map { it.slug }.sorted()}.",
      )
    }
    val owner = owners.singleOrNull() ?: return null
    if (owner.declaredFiles.baseline == null) {
      throw InvalidFallbackCapabilityError(
        "Platform pack '${owner.slug}' declares the code-review fallback without a code-review baseline.",
      )
    }
    return owner
  }

  fun resolveRequired(manifests: List<PlatformManifest>): PlatformManifest =
    resolveOptional(manifests) ?: throw InvalidFallbackCapabilityError(
      "Code-review routing requires exactly one manifest-declared fallback owner; found 0.",
    )

  private const val CODE_REVIEW_CAPABILITY = "code-review"
}

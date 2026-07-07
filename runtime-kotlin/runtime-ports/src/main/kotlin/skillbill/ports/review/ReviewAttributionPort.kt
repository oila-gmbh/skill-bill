package skillbill.ports.review

interface ReviewAttributionPort {
  fun routedSkillPlatformSlugs(): Map<String, String>
}

object EmptyReviewAttributionPort : ReviewAttributionPort {
  override fun routedSkillPlatformSlugs(): Map<String, String> = emptyMap()
}

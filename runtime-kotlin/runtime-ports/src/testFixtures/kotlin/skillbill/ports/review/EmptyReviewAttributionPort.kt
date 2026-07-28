package skillbill.ports.review

object EmptyReviewAttributionPort : ReviewAttributionPort {
  override fun routedSkillPlatformSlugs(): Map<String, String> = emptyMap()
}

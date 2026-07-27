package skillbill.ports.review

interface ReviewAttributionPort {
  fun routedSkillPlatformSlugs(): Map<String, String>
}

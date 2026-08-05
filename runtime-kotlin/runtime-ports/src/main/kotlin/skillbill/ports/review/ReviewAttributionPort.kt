package skillbill.ports.review

interface ReviewAttributionPort {
  fun routedSkillPlatformSlugs(): Map<String, String>

  fun knownPackSkillNames(): Set<String> = routedSkillPlatformSlugs().keys

  fun knownPlatformSlugs(): Set<String> = routedSkillPlatformSlugs().values.toSet()
}

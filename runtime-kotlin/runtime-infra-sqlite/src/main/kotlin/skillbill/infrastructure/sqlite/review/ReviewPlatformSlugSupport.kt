package skillbill.infrastructure.sqlite.review

import skillbill.review.normalizePlatformSlug

fun platformSlugFromRoutedSkill(
  routedSkill: String?,
  routedSkillPlatformSlugs: Map<String, String> = emptyMap(),
): String {
  val normalized = normalizePlatformSlug(routedSkill)
  if (normalized == "unknown") {
    return "unknown"
  }
  return normalizedRoutedSkillPlatformSlugs(routedSkillPlatformSlugs)
    .firstNotNullOfOrNull { (skillName, platformSlug) ->
      platformSlug.takeIf { normalized == skillName }
    }
    ?: "unknown"
}

private fun normalizedRoutedSkillPlatformSlugs(
  routedSkillPlatformSlugs: Map<String, String>,
): List<Pair<String, String>> = routedSkillPlatformSlugs
  .mapNotNull { (skillName, platformSlug) ->
    val normalizedSkillName = normalizePlatformSlug(skillName)
    val normalizedPlatformSlug = normalizePlatformSlug(platformSlug)
    if (normalizedSkillName == "unknown" || normalizedPlatformSlug == "unknown") {
      null
    } else {
      normalizedSkillName to normalizedPlatformSlug
    }
  }
  .sortedWith(compareByDescending<Pair<String, String>> { it.first.length }.thenBy { it.first })

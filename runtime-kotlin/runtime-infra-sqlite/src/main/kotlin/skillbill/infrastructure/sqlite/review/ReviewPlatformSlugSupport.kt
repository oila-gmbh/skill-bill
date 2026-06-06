package skillbill.infrastructure.sqlite.review

import skillbill.review.normalizePlatformSlug

fun platformSlugFromRoutedSkill(routedSkill: String?): String {
  val normalized = normalizePlatformSlug(routedSkill)
  return when {
    normalized == "unknown" -> "unknown"
    normalized.startsWith("bill-kmp-") -> "kmp"
    normalized.startsWith("bill-kotlin-") -> "kotlin"
    normalized.startsWith("bill-agent-config-") -> "agent-config"
    normalized.startsWith("bill-android-") -> "android"
    else -> "unknown"
  }
}

package skillbill.review

import skillbill.review.model.NormalizedStackLabel

fun normalizeRoutedSkill(rawValue: String?): String {
  val value = rawValue?.trim().orEmpty()
  if (value.isEmpty()) return "unrouted"
  val withoutNamespace = value.substringAfter(':')
  return if (
    withoutNamespace != value &&
    plausibleSkillSlug(withoutNamespace) &&
    value.substringBefore(':').matches(Regex("^[A-Za-z][A-Za-z0-9_-]*$"))
  ) {
    withoutNamespace
  } else {
    value
  }
}

fun normalizeStackLabel(rawValue: String?): NormalizedStackLabel {
  val value = rawValue?.trim().orEmpty()
  if (value.isEmpty()) return NormalizedStackLabel(stack = "unknown")
  val lower = value.lowercase()
  if (lower.contains("kmp") && lower.contains("kotlin") && lower.contains("fallback")) {
    return NormalizedStackLabel(
      stack = "kmp",
      detail = value,
      fallback = true,
      fallbackReason = "kotlin_quality_check_fallback",
    )
  }
  val slugSource = value
    .substringBefore("(")
    .substringBefore("->")
    .substringBefore("→")
    .substringBefore(" fallback")
    .trim()
  val slug = knownStackSlug(slugSource) ?: "unknown"
  val detail = value.takeIf { it.isNotBlank() && normalizeTelemetrySlug(it) != slug }
  return NormalizedStackLabel(stack = slug, detail = detail)
}

fun normalizePlatformSlug(rawValue: String?): String = normalizeStackLabel(rawValue).stack

fun normalizeTelemetrySlug(value: String): String = value
  .trim()
  .lowercase()
  .replace(Regex("[^a-z0-9]+"), "-")
  .trim('-')

fun normalizeScopeType(rawValue: String?): String {
  val normalized =
    rawValue
      ?.substringBefore("(")
      ?.trim()
      ?.lowercase()
      ?.replace(Regex("[^a-z0-9]+"), "_")
      ?.trim('_')
      ?.takeIf(String::isNotEmpty)
      ?: "unknown"
  return when (normalized) {
    "branch_diff", "branchdiff" -> "branch_diff"
    "unstaged_changes", "unstaged" -> "unstaged_changes"
    "staged_changes", "staged" -> "staged_changes"
    "file", "files" -> "files"
    "repository", "repo" -> "repo"
    else -> normalized
  }
}

private fun plausibleSkillSlug(value: String): Boolean = value.matches(Regex("^[a-z0-9][a-z0-9-]*$"))

private fun knownStackSlug(value: String): String? {
  val slug = normalizeTelemetrySlug(value).takeIf(String::isNotEmpty) ?: return null
  val tokens = slug.split("-").toSet()
  knownPlatformSlugs.firstOrNull { it in tokens }?.let { return it }
  if (slug.matches(Regex("^[a-z0-9][a-z0-9-]*$")) && value.trim().lowercase() == slug) {
    return slug
  }
  return null
}

private val knownPlatformSlugs = listOf(
  "kotlin",
  "kmp",
  "ios",
  "python",
  "php",
  "go",
  "android",
  "java",
  "ruby",
  "docs",
)

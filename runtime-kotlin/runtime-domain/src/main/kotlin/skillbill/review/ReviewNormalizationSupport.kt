package skillbill.review

fun normalizePlatformSlug(rawValue: String?): String = rawValue
  ?.trim()
  ?.lowercase()
  ?.replace(Regex("[^a-z0-9]+"), "-")
  ?.trim('-')
  ?.takeIf(String::isNotEmpty)
  ?: "unknown"

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

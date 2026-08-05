package skillbill.review

import skillbill.review.model.ImportedReview

const val UNRESOLVED_ATTRIBUTION: String = "unresolved"

const val EXECUTION_MODE_DELEGATED: String = "delegated"

enum class CanonicalScope(val wireValue: String) {
  WORKING_TREE("working_tree"),
  STAGED("staged"),
  COMMIT_RANGE("commit_range"),
  PULL_REQUEST("pull_request"),
  OTHER("other"),
}

data class CanonicalAttribution(
  val canonical: String,
  val raw: String?,
  val detail: String? = null,
) {
  val resolved: Boolean get() = canonical != UNRESOLVED_ATTRIBUTION
}

sealed class ReviewAttributionResolutionError(message: String) : IllegalArgumentException(message) {
  class MalformedVocabulary(
    val rawValue: String?,
    val vocabulary: String,
    val offendingEntry: String,
  ) : ReviewAttributionResolutionError(
    "Review attribution vocabulary '$vocabulary' contains the malformed entry '$offendingEntry' " +
      "while resolving '${rawValue.orEmpty()}'.",
  )
}

// Canonical stack vocabulary used when no catalog-derived slug set is available (the migration
// backfill runs below the port layer). Ingestion passes the discovered pack slugs unioned with this.
val canonicalPlatformSlugs: Set<String> = setOf(
  "kmp",
  "kotlin",
  "ios",
  "python",
  "php",
  "go",
  "rust",
  "typescript",
  "android",
  "java",
  "ruby",
  "docs",
  "generic",
)

private val packSkillPattern = Regex("bill-(?:[a-z0-9]+-)*code-review")

private val vocabularyEntryPattern = Regex("^[a-z0-9][a-z0-9-]*$")

fun resolveCanonicalRoutedSkill(rawValue: String?, knownPackSkillNames: Set<String>): CanonicalAttribution {
  requireWellFormedVocabulary(rawValue, "pack_skill_names", knownPackSkillNames)
  val value = rawValue?.trim().orEmpty()
  if (value.isEmpty()) return CanonicalAttribution(UNRESOLVED_ATTRIBUTION, rawValue)
  val candidates = packSkillPattern.findAll(value.lowercase()).map { it.value }.toSet()
  val candidate = candidates.singleOrNull() ?: return CanonicalAttribution(UNRESOLVED_ATTRIBUTION, rawValue)
  if (knownPackSkillNames.isNotEmpty() && candidate !in knownPackSkillNames) {
    return CanonicalAttribution(UNRESOLVED_ATTRIBUTION, rawValue)
  }
  return CanonicalAttribution(candidate, rawValue)
}

fun resolveCanonicalStack(
  rawValue: String?,
  knownPlatformSlugs: Set<String> = canonicalPlatformSlugs,
): CanonicalAttribution {
  requireWellFormedVocabulary(rawValue, "platform_slugs", knownPlatformSlugs)
  val value = rawValue?.trim().orEmpty()
  if (value.isEmpty()) return CanonicalAttribution(UNRESOLVED_ATTRIBUTION, rawValue)
  val vocabulary = knownPlatformSlugs.ifEmpty { canonicalPlatformSlugs }
  val tokens = normalizeTelemetrySlug(value).split("-").filter(String::isNotEmpty).toSet()
  if ("kmp" in tokens || ("kotlin" in tokens && "multiplatform" in tokens)) {
    return CanonicalAttribution("kmp", rawValue)
  }
  val matches = vocabulary.filter { it in tokens }
  val canonical = matches.singleOrNull() ?: return CanonicalAttribution(UNRESOLVED_ATTRIBUTION, rawValue)
  return CanonicalAttribution(canonical, rawValue)
}

fun resolveCanonicalScope(rawValue: String?): CanonicalAttribution {
  val value = rawValue?.trim().orEmpty()
  if (value.isEmpty()) return CanonicalAttribution(UNRESOLVED_ATTRIBUTION, rawValue)
  val head = value.substringBefore("(").trim()
  val detail = value.removePrefix(head).trim().trim('(', ')').trim().takeIf(String::isNotBlank)
  val slug = normalizeTelemetrySlug(head)
  val scope = matchCanonicalScope(slug) ?: return CanonicalAttribution(UNRESOLVED_ATTRIBUTION, rawValue, detail)
  return CanonicalAttribution(scope.wireValue, rawValue, detail)
}

// execution_mode is derived, never defaulted: an explicit reported value wins, otherwise the run's
// own specialist-review evidence proves delegation, otherwise the value is explicitly unresolved.
fun resolveExecutionMode(reportedExecutionMode: String?, specialistReviews: List<String>): String =
  reportedExecutionMode?.trim()?.takeIf(String::isNotEmpty)
    ?: if (specialistReviews.isNotEmpty()) EXECUTION_MODE_DELEGATED else UNRESOLVED_ATTRIBUTION

fun ImportedReview.withCanonicalAttribution(
  knownPackSkillNames: Set<String>,
  knownPlatformSlugs: Set<String>,
): ImportedReview {
  val scope = resolveCanonicalScope(detectedScope)
  return copy(
    routedSkillCanonical = resolveCanonicalRoutedSkill(routedSkill, knownPackSkillNames).canonical,
    detectedStackCanonical = resolveCanonicalStack(detectedStack, knownPlatformSlugs).canonical,
    detectedScopeCanonical = scope.canonical,
    detectedScopeDetail = scope.detail,
  )
}

@Suppress("ReturnCount")
private fun matchCanonicalScope(slug: String): CanonicalScope? {
  if (slug.isEmpty()) return null
  // "unstaged" is checked before "staged" because it contains it.
  if ("unstaged" in slug || "working-tree" in slug || "working-dir" in slug || slug == "worktree") {
    return CanonicalScope.WORKING_TREE
  }
  if ("staged" in slug || "index" == slug) return CanonicalScope.STAGED
  if ("pull-request" in slug || slug == "pr" || "pull-req" in slug) return CanonicalScope.PULL_REQUEST
  if ("commit-range" in slug || "branch-diff" in slug || ("commit" in slug && "range" in slug)) {
    return CanonicalScope.COMMIT_RANGE
  }
  // Positive other-scope rule: a recognized scope kind that is deliberately none of the four above.
  if (slug == "other" || "repo" in slug || "repository" in slug || slug == "file" || slug == "files") {
    return CanonicalScope.OTHER
  }
  return null
}

private fun requireWellFormedVocabulary(rawValue: String?, vocabulary: String, entries: Set<String>) {
  entries.forEach { entry ->
    if (!entry.matches(vocabularyEntryPattern)) {
      throw ReviewAttributionResolutionError.MalformedVocabulary(rawValue, vocabulary, entry)
    }
  }
}

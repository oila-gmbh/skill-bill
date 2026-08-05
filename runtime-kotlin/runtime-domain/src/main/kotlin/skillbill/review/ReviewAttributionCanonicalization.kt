package skillbill.review

import skillbill.review.model.CanonicalAttribution
import skillbill.review.model.CanonicalScope
import skillbill.review.model.ImportedReview
import skillbill.review.model.ReviewAttributionResolutionError

const val UNRESOLVED_ATTRIBUTION: String = "unresolved"

const val EXECUTION_MODE_DELEGATED: String = "delegated"

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

// Canonical pack-skill vocabulary, derived from the canonical slugs so the migration backfill (which
// runs below the port layer) checks membership against the same names ingestion accepts. Ingestion
// unions this with the discovered pack skill names.
val canonicalPackSkillNames: Set<String> = canonicalPlatformSlugs.map { slug -> "bill-$slug-code-review" }.toSet()

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

private class ScopeMatchRule(
  val scope: CanonicalScope,
  val exact: Set<String> = emptySet(),
  val contains: Set<String> = emptySet(),
  val prefixes: Set<String> = emptySet(),
  val containsAll: Set<String> = emptySet(),
) {
  fun matches(slug: String): Boolean = when {
    slug in exact -> true
    contains.any { term -> term in slug } -> true
    prefixes.any { prefix -> slug.startsWith(prefix) } -> true
    else -> containsAll.isNotEmpty() && containsAll.all { term -> term in slug }
  }
}

// Ordered scope vocabulary: the first matching rule wins, so narrower terms must come first —
// "unstaged" contains "staged", and "pr-diff" must not fall through to the commit-range rule.
private val scopeMatchRules: List<ScopeMatchRule> = listOf(
  ScopeMatchRule(
    scope = CanonicalScope.WORKING_TREE,
    exact = setOf("worktree"),
    contains = setOf("unstaged", "working-tree", "working-dir"),
  ),
  ScopeMatchRule(scope = CanonicalScope.STAGED, exact = setOf("index"), contains = setOf("staged")),
  // "pr-diff" is the governed pull-request label emitted by code-review-shell.yaml.
  ScopeMatchRule(
    scope = CanonicalScope.PULL_REQUEST,
    exact = setOf("pr"),
    contains = setOf("pull-req"),
    prefixes = setOf("pr-"),
  ),
  ScopeMatchRule(
    scope = CanonicalScope.COMMIT_RANGE,
    contains = setOf("commit-range", "branch-diff"),
    containsAll = setOf("commit", "range"),
  ),
  // Positive other-scope rule: a recognized scope kind that is deliberately none of the four above.
  ScopeMatchRule(scope = CanonicalScope.OTHER, exact = setOf("other", "file", "files"), contains = setOf("repo")),
)

private fun matchCanonicalScope(slug: String): CanonicalScope? =
  if (slug.isEmpty()) null else scopeMatchRules.firstOrNull { rule -> rule.matches(slug) }?.scope

private fun requireWellFormedVocabulary(rawValue: String?, vocabulary: String, entries: Set<String>) {
  entries.forEach { entry ->
    if (!entry.matches(vocabularyEntryPattern)) {
      throw ReviewAttributionResolutionError.MalformedVocabulary(rawValue, vocabulary, entry)
    }
  }
}

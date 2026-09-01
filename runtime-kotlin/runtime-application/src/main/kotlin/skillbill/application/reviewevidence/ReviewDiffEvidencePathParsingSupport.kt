package skillbill.application.reviewevidence

internal const val REVIEW_DIFF_OLD_START_GROUP = 1
internal const val REVIEW_DIFF_OLD_COUNT_GROUP = 2
internal const val REVIEW_DIFF_NEW_START_GROUP = 3
internal const val REVIEW_DIFF_NEW_COUNT_GROUP = 4

private const val REVIEW_DIFF_OLD_PREFIX = "a/"
private const val REVIEW_DIFF_NEW_PREFIX = "b/"

private val REVIEW_DIFF_HEADER_PATH = Regex("(?m)^\\+\\+\\+ (.+)$")
private val REVIEW_DIFF_OLD_HEADER_PATH = Regex("(?m)^--- (.+)$")
private val REVIEW_DIFF_RENAME_FROM = Regex("(?m)^rename from (.+)$")
private val REVIEW_DIFF_RENAME_TO = Regex("(?m)^rename to (.+)$")
private val REVIEW_DIFF_COPY_FROM = Regex("(?m)^copy from (.+)$")
private val REVIEW_DIFF_COPY_TO = Regex("(?m)^copy to (.+)$")

internal data class ReviewDiffRecordPaths(val old: String?, val new: String?, val authoritative: String)

internal fun reviewDiffRecordPaths(record: String): ReviewDiffRecordPaths {
  val oldHeaderValue = REVIEW_DIFF_OLD_HEADER_PATH.find(record)?.groupValues?.get(1)
  val newHeaderValue = REVIEW_DIFF_HEADER_PATH.find(record)?.groupValues?.get(1)
  val oldAbsent = oldHeaderValue?.trim() == "/dev/null"
  val newAbsent = newHeaderValue?.trim() == "/dev/null"
  val oldSources = listOfNotNull(
    oldHeaderValue?.let { reviewDiffRepositoryPath(it, REVIEW_DIFF_OLD_PREFIX) },
    REVIEW_DIFF_RENAME_FROM.find(record)?.groupValues?.get(1)?.let { reviewDiffRepositoryPath(it, null) },
    REVIEW_DIFF_COPY_FROM.find(record)?.groupValues?.get(1)?.let { reviewDiffRepositoryPath(it, null) },
  )
  val newSources = listOfNotNull(
    newHeaderValue?.let { reviewDiffRepositoryPath(it, REVIEW_DIFF_NEW_PREFIX) },
    REVIEW_DIFF_RENAME_TO.find(record)?.groupValues?.get(1)?.let { reviewDiffRepositoryPath(it, null) },
    REVIEW_DIFF_COPY_TO.find(record)?.groupValues?.get(1)?.let { reviewDiffRepositoryPath(it, null) },
  )
  val headerPaths = parseReviewDiffHeader(record.lineSequence().first(), oldSources, newSources)
  require(!(oldAbsent && newAbsent)) { "Git diff record cannot have /dev/null on both sides." }
  val old = if (oldAbsent) null else agreeReviewDiffPaths("old", oldSources + listOfNotNull(headerPaths?.first))
  val new = if (newAbsent) null else agreeReviewDiffPaths("new", newSources + listOfNotNull(headerPaths?.second))
  val authoritative = new ?: old
    ?: throw IllegalArgumentException("Malformed Git diff record has no attributable repository path.")
  return ReviewDiffRecordPaths(old, new, authoritative)
}

private fun parseReviewDiffHeader(
  line: String,
  corroboratedOld: List<String>,
  corroboratedNew: List<String>,
): Pair<String, String>? {
  val body = line.removePrefix("diff --git ").takeIf { it != line } ?: return null
  val tokens = parseReviewDiffGitTokens(body)
  if (tokens.size == 2) {
    return requireNotNull(reviewDiffRepositoryPath(tokens[0], REVIEW_DIFF_OLD_PREFIX)) to
      requireNotNull(reviewDiffRepositoryPath(tokens[1], REVIEW_DIFF_NEW_PREFIX))
  }
  val candidates = Regex(" b/").findAll(body).mapNotNull { boundary ->
    runCatching {
      requireNotNull(reviewDiffRepositoryPath(body.substring(0, boundary.range.first), REVIEW_DIFF_OLD_PREFIX)) to
        requireNotNull(reviewDiffRepositoryPath(body.substring(boundary.range.first + 1), REVIEW_DIFF_NEW_PREFIX))
    }.getOrNull()
  }.filter { (old, new) ->
    (corroboratedOld.isEmpty() || old in corroboratedOld) &&
      (corroboratedNew.isEmpty() || new in corroboratedNew) &&
      (corroboratedOld.isNotEmpty() || corroboratedNew.isNotEmpty() || old == new)
  }.distinct().toList()
  require(candidates.size == 1) { "Ambiguous Git diff header cannot establish repository path ownership." }
  return candidates.single()
}

private fun agreeReviewDiffPaths(side: String, paths: List<String>): String? {
  val distinct = paths.distinct()
  require(distinct.size <= 1) { "Git diff $side path sources disagree: ${distinct.joinToString()}" }
  return distinct.singleOrNull()
}

package skillbill.application.reviewevidence

import skillbill.review.context.model.ReviewChangedHunk

/** Immutable, single-parse evidence used by routing, ownership, preparation, and add-on selection. */
internal data class ReviewDiffEvidence(
  val hunks: List<ReviewChangedHunk>,
  val files: List<ReviewChangedFileEvidence>,
) {
  init {
    require(hunks.isNotEmpty()) { "The authoritative review diff contains no parseable changed hunks." }
  }

  fun ownedFiles(paths: Set<String>): List<ReviewChangedFileEvidence> = files.filter { it.path in paths }

  companion object {
    fun parseAttributable(diff: String): ReviewDiffEvidence? = parseAttributableReviewDiffEvidence(diff)

    fun parse(diff: String): ReviewDiffEvidence = parseReviewDiffEvidence(diff)
  }
}

/** One commit's Git-reported identity paired with its incremental diff against its first parent. */
internal data class RawCommitDiff(
  val commitSha: String,
  val parentSha: String,
  val subject: String,
  val diff: String,
)

internal data class ReviewChangedFileEvidence(
  val path: String,
  val changedContent: String,
  val fullRecord: String,
  val oldPath: String? = path,
  val newPath: String? = path,
)

internal fun diffRecords(diff: String): List<String> {
  val normalized = diff.replace("\r\n", "\n")
  val gitRecords = normalized.split(Regex("(?m)(?=^diff --git )")).filter { it.startsWith("diff --git ") }
  return gitRecords.ifEmpty {
    normalized.split(Regex("(?m)(?=^\\+\\+\\+ )")).filter { it.startsWith("+++ ") }
  }
}

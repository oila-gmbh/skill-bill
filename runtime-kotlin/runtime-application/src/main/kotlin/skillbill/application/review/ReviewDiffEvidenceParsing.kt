package skillbill.application.review

import skillbill.review.context.model.ReviewChangedHunk

internal fun parseAttributableReviewDiffEvidence(diff: String): ReviewDiffEvidence? =
  diffRecords(diff).takeIf { it.isNotEmpty() }?.let {
    runCatching { parseReviewDiffEvidence(it.joinToString("\n")) }.getOrNull()
  }

internal fun parseReviewDiffEvidence(diff: String): ReviewDiffEvidence {
  val normalized = diff.replace("\r\n", "\n")
  val records = diffRecords(normalized)
  require(records.isNotEmpty()) { "The authoritative review diff contains no attributable diff records." }
  val hunks = mutableListOf<ReviewChangedHunk>()
  val files = records.map { record ->
    val paths = reviewDiffRecordPaths(record)
    val path = paths.authoritative
    val changedContent = record.lineSequence()
      .filter { (it.startsWith("+") || it.startsWith("-")) && !it.startsWith("+++") && !it.startsWith("---") }
      .joinToString("\n")
    val header = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")
    val lines = record.lines()
    var index = 0
    var found = false
    while (index < lines.size) {
      val match = header.find(lines[index])
      if (match == null) {
        index += 1
        continue
      }
      found = true
      val content = buildString {
        appendLine(lines[index++])
        while (index < lines.size && !lines[index].startsWith("@@ ")) appendLine(lines[index++])
      }.removeSuffix("\n")
      hunks += ReviewChangedHunk(
        path,
        match.groupValues[REVIEW_DIFF_OLD_START_GROUP].toInt(),
        match.groupValues[REVIEW_DIFF_OLD_COUNT_GROUP].ifBlank { "1" }.toInt(),
        match.groupValues[REVIEW_DIFF_NEW_START_GROUP].toInt(),
        match.groupValues[REVIEW_DIFF_NEW_COUNT_GROUP].ifBlank { "1" }.toInt(),
        content,
      )
    }
    if (!found) hunks += ReviewChangedHunk(path, 0, 0, 0, 0, record.trimEnd())
    ReviewChangedFileEvidence(path, changedContent, record.trimEnd(), paths.old, paths.new)
  }
  return ReviewDiffEvidence(hunks, files)
}

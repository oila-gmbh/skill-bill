package skillbill.application.review

import skillbill.review.context.model.ReviewChangedHunk

/** Immutable, single-parse evidence used by routing, ownership, preparation, and add-on selection. */
internal data class ReviewDiffEvidence(
  val hunks: List<ReviewChangedHunk>,
  val files: List<ReviewChangedFileEvidence>,
) {
  init {
    require(hunks.isNotEmpty()) { "The authoritative review diff contains no parseable changed hunks." }
  }

  fun ownedDiff(paths: Set<String>): String = hunks.filter { it.path in paths }.joinToString("\n") { it.content }

  companion object {
    fun parse(diff: String): ReviewDiffEvidence {
      val normalized = diff.replace("\r\n", "\n")
      val records = normalized
        .split(Regex("(?m)(?=^diff --git |^\\+\\+\\+ b/)"))
        .filter { DIFF_PATH.containsMatchIn(it) }
      val hunks = mutableListOf<ReviewChangedHunk>()
      val files = records.mapNotNull { record ->
        val path = DIFF_PATH.find(record)?.groupValues?.get(1) ?: return@mapNotNull null
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
            match.groupValues[OLD_START_GROUP].toInt(),
            match.groupValues[OLD_COUNT_GROUP].ifBlank { "1" }.toInt(),
            match.groupValues[NEW_START_GROUP].toInt(),
            match.groupValues[NEW_COUNT_GROUP].ifBlank { "1" }.toInt(),
            content,
          )
        }
        if (!found) hunks += ReviewChangedHunk(path, 0, 0, 0, 0, record.trimEnd())
        if (RoutingSignalPathMatcher.isIgnored(path)) null else ReviewChangedFileEvidence(path, changedContent)
      }
      return ReviewDiffEvidence(hunks, files)
    }

    private val DIFF_PATH = Regex("^\\+\\+\\+ b/(.+)$", RegexOption.MULTILINE)
    private const val OLD_START_GROUP = 1
    private const val OLD_COUNT_GROUP = 2
    private const val NEW_START_GROUP = 3
    private const val NEW_COUNT_GROUP = 4
  }
}

internal data class ReviewChangedFileEvidence(val path: String, val changedContent: String)

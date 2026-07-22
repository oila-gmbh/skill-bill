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

  fun ownedFiles(paths: Set<String>): List<ReviewChangedFileEvidence> = files.filter { it.path in paths }

  companion object {
    fun parse(diff: String): ReviewDiffEvidence {
      val normalized = diff.replace("\r\n", "\n")
      val gitRecords = normalized.split(Regex("(?m)(?=^diff --git )")).filter { it.startsWith("diff --git ") }
      val records = gitRecords.ifEmpty {
        normalized.split(Regex("(?m)(?=^\\+\\+\\+ )")).filter { it.startsWith("+++ ") }
      }
      require(records.isNotEmpty()) { "The authoritative review diff contains no attributable diff records." }
      val hunks = mutableListOf<ReviewChangedHunk>()
      val files = records.map { record ->
        val path = recordPath(record)
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
        ReviewChangedFileEvidence(path, changedContent, record.trimEnd())
      }
      return ReviewDiffEvidence(hunks, files)
    }

    private fun recordPath(record: String): String {
      val newPath = HEADER_PATH.find(record)?.groupValues?.get(1)?.takeUnless { it == "/dev/null" }
      val oldPath = OLD_HEADER_PATH.find(record)?.groupValues?.get(1)?.takeUnless { it == "/dev/null" }
      val renameTo = RENAME_TO.find(record)?.groupValues?.get(1)
      val header = DIFF_HEADER.matchEntire(record.lineSequence().first())
      val raw = newPath ?: renameTo ?: oldPath
        ?: header?.let { it.groupValues[NEW_QUOTED_PATH_GROUP].ifBlank { it.groupValues[NEW_PATH_GROUP] } }
        ?: throw IllegalArgumentException("Malformed Git diff record has no attributable repository path.")
      return decodeGitPath(raw).removePrefix("a/").removePrefix("b/").replace('\\', '/')
        .also { require(it.isNotBlank() && !it.startsWith("/") && ".." !in it.split('/')) {
          "Malformed Git diff record has a non-repository path '$it'."
        } }
    }

    private fun decodeGitPath(value: String): String {
      val trimmed = value.trim()
      if (!(trimmed.startsWith('"') && trimmed.endsWith('"'))) return trimmed
      val body = trimmed.substring(1, trimmed.length - 1)
      return OCTAL_OR_ESCAPE.replace(body) { match ->
        val token = match.value.removePrefix("\\")
        when {
          token.length == GIT_OCTAL_WIDTH && token.all { it in '0'..'7' } ->
            token.toInt(GIT_OCTAL_RADIX).toChar().toString()
          token == "t" -> "\t"
          token == "n" -> "\n"
          token == "r" -> "\r"
          else -> token
        }
      }
    }

    private val HEADER_PATH = Regex("(?m)^\\+\\+\\+ (.+)$")
    private val OLD_HEADER_PATH = Regex("(?m)^--- (.+)$")
    private val RENAME_TO = Regex("(?m)^rename to (.+)$")
    private val DIFF_HEADER = Regex("diff --git (?:\"([^\"]+)\"|(\\S+)) (?:\"([^\"]+)\"|(\\S+))")
    private val OCTAL_OR_ESCAPE = Regex("\\\\(?:[0-7]{3}|.)")
    private const val OLD_START_GROUP = 1
    private const val OLD_COUNT_GROUP = 2
    private const val NEW_START_GROUP = 3
    private const val NEW_COUNT_GROUP = 4
    private const val NEW_QUOTED_PATH_GROUP = 3
    private const val NEW_PATH_GROUP = 4
    private const val GIT_OCTAL_WIDTH = 3
    private const val GIT_OCTAL_RADIX = 8
  }
}

internal data class ReviewChangedFileEvidence(
  val path: String,
  val changedContent: String,
  val fullRecord: String,
)

package skillbill.application.review

import skillbill.review.context.model.ReviewChangedHunk
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

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
        val paths = recordPaths(record)
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
            match.groupValues[OLD_START_GROUP].toInt(),
            match.groupValues[OLD_COUNT_GROUP].ifBlank { "1" }.toInt(),
            match.groupValues[NEW_START_GROUP].toInt(),
            match.groupValues[NEW_COUNT_GROUP].ifBlank { "1" }.toInt(),
            content,
          )
        }
        if (!found) hunks += ReviewChangedHunk(path, 0, 0, 0, 0, record.trimEnd())
        ReviewChangedFileEvidence(path, changedContent, record.trimEnd(), paths.old, paths.new)
      }
      return ReviewDiffEvidence(hunks, files)
    }

    private fun recordPaths(record: String): RecordPaths {
      val oldHeaderValue = OLD_HEADER_PATH.find(record)?.groupValues?.get(1)
      val newHeaderValue = HEADER_PATH.find(record)?.groupValues?.get(1)
      val oldAbsent = oldHeaderValue?.trim() == "/dev/null"
      val newAbsent = newHeaderValue?.trim() == "/dev/null"
      val oldSources = listOfNotNull(
        oldHeaderValue?.fileHeaderPath(OLD_PREFIX),
        RENAME_FROM.find(record)?.groupValues?.get(1)?.extendedHeaderPath(),
        COPY_FROM.find(record)?.groupValues?.get(1)?.extendedHeaderPath(),
      )
      val newSources = listOfNotNull(
        newHeaderValue?.fileHeaderPath(NEW_PREFIX),
        RENAME_TO.find(record)?.groupValues?.get(1)?.extendedHeaderPath(),
        COPY_TO.find(record)?.groupValues?.get(1)?.extendedHeaderPath(),
      )
      val headerPaths = parseDiffHeader(record.lineSequence().first(), oldSources, newSources)
      require(!(oldAbsent && newAbsent)) { "Git diff record cannot have /dev/null on both sides." }
      val old = if (oldAbsent) null else agree("old", oldSources + listOfNotNull(headerPaths?.first))
      val new = if (newAbsent) null else agree("new", newSources + listOfNotNull(headerPaths?.second))
      val authoritative = new ?: old
        ?: throw IllegalArgumentException("Malformed Git diff record has no attributable repository path.")
      return RecordPaths(old, new, authoritative)
    }

    private fun String.fileHeaderPath(prefix: String): String? = repositoryPath(prefix)

    private fun String.extendedHeaderPath(): String? = repositoryPath(prefix = null)

    private fun String.repositoryPath(prefix: String?): String? = takeUnless { it.trim() == "/dev/null" }
      ?.let(::decodeGitPath)?.let { path ->
        if (prefix == null) path else {
          require(path.startsWith(prefix)) { "Git path source must carry the '$prefix' prefix." }
          path.removePrefix(prefix)
        }
      }
      ?.also { require(it.isNotBlank() && !it.startsWith("/") && ".." !in it.split('/')) {
          "Malformed Git diff record has a non-repository path '$it'."
        } }

    private fun parseDiffHeader(
      line: String,
      corroboratedOld: List<String>,
      corroboratedNew: List<String>,
    ): Pair<String, String>? {
      val body = line.removePrefix("diff --git ").takeIf { it != line } ?: return null
      val tokens = parseGitTokens(body)
      if (tokens.size == 2) {
        return requireNotNull(tokens[0].repositoryPath(OLD_PREFIX)) to
          requireNotNull(tokens[1].repositoryPath(NEW_PREFIX))
      }
      val candidates = Regex(" b/").findAll(body).mapNotNull { boundary ->
        runCatching {
          requireNotNull(body.substring(0, boundary.range.first).repositoryPath(OLD_PREFIX)) to
            requireNotNull(body.substring(boundary.range.first + 1).repositoryPath(NEW_PREFIX))
        }.getOrNull()
      }.filter { (old, new) ->
        (corroboratedOld.isEmpty() || old in corroboratedOld) &&
          (corroboratedNew.isEmpty() || new in corroboratedNew) &&
          (corroboratedOld.isNotEmpty() || corroboratedNew.isNotEmpty() || old == new)
      }.distinct().toList()
      require(candidates.size == 1) { "Ambiguous Git diff header cannot establish repository path ownership." }
      return candidates.single()
    }

    private fun agree(side: String, paths: List<String>): String? {
      val distinct = paths.distinct()
      require(distinct.size <= 1) { "Git diff $side path sources disagree: ${distinct.joinToString()}" }
      return distinct.singleOrNull()
    }

    private fun parseGitTokens(value: String): List<String> {
      val tokens = mutableListOf<String>()
      var index = 0
      while (index < value.length) {
        while (index < value.length && value[index].isWhitespace()) index++
        if (index == value.length) break
        val start = index
        if (value[index] == '"') {
          index++
          var closed = false
          while (index < value.length) {
            if (value[index] == '\\') {
              require(index + 1 < value.length) { "Malformed quoted Git path ends with an escape." }
              index += 2
            } else if (value[index++] == '"') {
              closed = true
              break
            }
          }
          require(closed) { "Malformed quoted Git path is missing its closing quote." }
        } else {
          while (index < value.length && !value[index].isWhitespace()) index++
        }
        tokens += value.substring(start, index)
      }
      return tokens
    }

    @Suppress("CyclomaticComplexMethod")
    private fun decodeGitPath(value: String): String {
      val trimmed = value.trim()
      if (!(trimmed.startsWith('"') && trimmed.endsWith('"'))) return trimmed
      val body = trimmed.substring(1, trimmed.length - 1)
      val decoded = StringBuilder()
      var index = 0
      while (index < body.length) {
        if (body[index] != '\\') {
          decoded.append(body[index++])
          continue
        }
        val bytes = ByteArrayOutputStream()
        while (index + GIT_OCTAL_WIDTH < body.length && body[index] == '\\' &&
          body.substring(index + 1, index + 1 + GIT_OCTAL_WIDTH).all { it in '0'..'7' }
        ) {
          bytes.write(body.substring(index + 1, index + 1 + GIT_OCTAL_WIDTH).toInt(GIT_OCTAL_RADIX))
          index += GIT_OCTAL_WIDTH + 1
        }
        if (bytes.size() > 0) {
          val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
          val utf8 = runCatching { decoder.decode(ByteBuffer.wrap(bytes.toByteArray())) }
            .getOrElse { throw IllegalArgumentException("Quoted Git path contains invalid UTF-8 bytes.", it) }
          decoded.append(utf8)
        } else {
          index++
          require(index < body.length) { "Malformed quoted Git path ends with an escape." }
          decoded.append(when (val escaped = body[index++]) {
            'a' -> '\u0007'; 'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t';
            'v' -> '\u000b'; '\\' -> '\\'; '"' -> '"'
            else -> throw IllegalArgumentException("Unsupported quoted Git path escape '\\$escaped'.")
          })
        }
      }
      return decoded.toString()
    }

    private val HEADER_PATH = Regex("(?m)^\\+\\+\\+ (.+)$")
    private val OLD_HEADER_PATH = Regex("(?m)^--- (.+)$")
    private val RENAME_FROM = Regex("(?m)^rename from (.+)$")
    private val RENAME_TO = Regex("(?m)^rename to (.+)$")
    private val COPY_FROM = Regex("(?m)^copy from (.+)$")
    private val COPY_TO = Regex("(?m)^copy to (.+)$")
    private const val OLD_START_GROUP = 1
    private const val OLD_COUNT_GROUP = 2
    private const val NEW_START_GROUP = 3
    private const val NEW_COUNT_GROUP = 4
    private const val GIT_OCTAL_WIDTH = 3
    private const val GIT_OCTAL_RADIX = 8
    private const val OLD_PREFIX = "a/"
    private const val NEW_PREFIX = "b/"
  }
}

private data class RecordPaths(val old: String?, val new: String?, val authoritative: String)

internal data class ReviewChangedFileEvidence(
  val path: String,
  val changedContent: String,
  val fullRecord: String,
  val oldPath: String? = path,
  val newPath: String? = path,
)

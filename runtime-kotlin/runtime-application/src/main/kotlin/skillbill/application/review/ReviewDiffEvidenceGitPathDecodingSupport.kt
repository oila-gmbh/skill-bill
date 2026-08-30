package skillbill.application.review

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

private const val REVIEW_DIFF_GIT_OCTAL_WIDTH = 3
private const val REVIEW_DIFF_GIT_OCTAL_RADIX = 8

internal fun parseReviewDiffGitTokens(value: String): List<String> {
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

internal fun decodeReviewDiffGitPath(value: String): String {
  val trimmed = value.trim()
  if (!(trimmed.startsWith('"') && trimmed.endsWith('"'))) return trimmed
  return decodeReviewDiffQuotedGitPath(trimmed.substring(1, trimmed.length - 1))
}

internal fun reviewDiffRepositoryPath(value: String, prefix: String?): String? =
  value.takeUnless { it.trim() == "/dev/null" }
    ?.let(::decodeReviewDiffGitPath)?.let { path ->
      if (prefix == null) {
        path
      } else {
        require(path.startsWith(prefix)) { "Git path source must carry the '$prefix' prefix." }
        path.removePrefix(prefix)
      }
    }
    ?.also {
      require(it.isNotBlank() && !it.startsWith("/") && ".." !in it.split('/')) {
        "Malformed Git diff record has a non-repository path '$it'."
      }
    }

private fun decodeReviewDiffQuotedGitPath(body: String): String {
  val decoded = StringBuilder()
  var index = 0
  while (index < body.length) {
    val nextIndex = decodeReviewDiffQuotedGitPathSegment(body, index, decoded)
    index = nextIndex
  }
  return decoded.toString()
}

private fun decodeReviewDiffQuotedGitPathSegment(body: String, index: Int, decoded: StringBuilder): Int {
  if (body[index] != '\\') {
    decoded.append(body[index])
    return index + 1
  }
  val octal = consumeReviewDiffGitOctalBytes(body, index)
  if (octal != null) {
    decoded.append(decodeReviewDiffGitOctalUtf8(octal.bytes))
    return octal.nextIndex
  }
  val escapedIndex = index + 1
  require(escapedIndex < body.length) { "Malformed quoted Git path ends with an escape." }
  decoded.append(decodeReviewDiffGitEscapeChar(body[escapedIndex]))
  return escapedIndex + 1
}

private data class ReviewDiffGitOctalBytes(val bytes: ByteArray, val nextIndex: Int)

private fun consumeReviewDiffGitOctalBytes(body: String, startIndex: Int): ReviewDiffGitOctalBytes? {
  val bytes = ByteArrayOutputStream()
  var index = startIndex
  while (index + REVIEW_DIFF_GIT_OCTAL_WIDTH < body.length && body[index] == '\\' &&
    body.substring(index + 1, index + 1 + REVIEW_DIFF_GIT_OCTAL_WIDTH).all { it in '0'..'7' }
  ) {
    bytes.write(body.substring(index + 1, index + 1 + REVIEW_DIFF_GIT_OCTAL_WIDTH).toInt(REVIEW_DIFF_GIT_OCTAL_RADIX))
    index += REVIEW_DIFF_GIT_OCTAL_WIDTH + 1
  }
  return if (bytes.size() > 0) ReviewDiffGitOctalBytes(bytes.toByteArray(), index) else null
}

private fun decodeReviewDiffGitOctalUtf8(raw: ByteArray): CharSequence {
  val decoder = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
  return runCatching { decoder.decode(ByteBuffer.wrap(raw)) }
    .getOrElse { throw IllegalArgumentException("Quoted Git path contains invalid UTF-8 bytes.", it) }
}

private fun decodeReviewDiffGitEscapeChar(escaped: Char): Char = when (escaped) {
  'a' -> '\u0007'
  'b' -> '\b'
  'f' -> '\u000c'
  'n' -> '\n'
  'r' -> '\r'
  't' -> '\t'
  'v' -> '\u000b'
  '\\' -> '\\'
  '"' -> '"'
  else -> throw IllegalArgumentException("Unsupported quoted Git path escape '\\$escaped'.")
}

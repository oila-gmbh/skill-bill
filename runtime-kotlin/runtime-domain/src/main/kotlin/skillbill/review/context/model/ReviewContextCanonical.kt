@file:Suppress("SpreadOperator", "MagicNumber")

package skillbill.review.context.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal val SHA256_HEX = Regex("[a-f0-9]{64}")

fun requireRepositoryRelativePath(path: String) {
  require(path.isNotEmpty() && !path.startsWith('/') && !path.startsWith('\\')) {
    "Review paths must be repository-relative."
  }
  require('\u0000' !in path && path.hasWellFormedUtf16()) {
    "Review paths must contain valid Unicode and no NUL."
  }
  require(!WINDOWS_ABSOLUTE_PATH.matches(path)) { "Review paths must be repository-relative." }
  require(repositoryPathSegments(path).none { it == "." || it == ".." }) {
    "Review paths must use non-traversing Git path components."
  }
}

internal fun repositoryPathSegments(path: String): List<String> = path.split('/', '\\').filter { it.isNotEmpty() }

private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[/\\\\].*")

private fun String.hasWellFormedUtf16(): Boolean {
  var index = 0
  while (index < length) {
    val current = this[index]
    when {
      Character.isHighSurrogate(current) -> {
        if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return false
        index += 2
      }
      Character.isLowSurrogate(current) -> return false
      else -> index++
    }
  }
  return true
}

/** Injective UTF-8 length-prefixed encoding used by every content-addressed review identity. */
internal fun canonicalFields(vararg values: Any): String = canonicalFieldList(values.asList())

/** List form of [canonicalFields] for callers that already hold a collection. */
internal fun canonicalFieldList(values: List<Any>): String = values.joinToString("") { value ->
  val text = value.toString()
  "${text.toByteArray(StandardCharsets.UTF_8).size}:$text"
}

/** JSON scalar encoding keeps path data from becoming launch-payload structure. */
fun structuredString(value: String): String = buildString {
  append('"')
  value.forEach { char ->
    when (char) {
      '"' -> append("\\\"")
      '\\' -> append("\\\\")
      '\b' -> append("\\b")
      '\u000c' -> append("\\f")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
    }
  }
  append('"')
}

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
  .digest(value.toByteArray(StandardCharsets.UTF_8))
  .joinToString("") { byte -> "%02x".format(byte) }


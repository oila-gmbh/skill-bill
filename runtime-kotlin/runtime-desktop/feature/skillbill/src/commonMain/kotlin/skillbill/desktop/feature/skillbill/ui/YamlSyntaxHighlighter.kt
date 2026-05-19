@file:Suppress("MagicNumber")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

/**
 * SKILL-47 AC6 — color palette for the YAML-aware schema viewer.
 *
 * Passed in by the call site so the highlighter stays a pure function and can
 * be unit-tested without a Compose runtime. Production callers reuse the
 * existing workspace palette (see [contractYamlColors] in SkillBillFrame.kt)
 * so light/dark theming follows the rest of the editor pane.
 */
internal data class YamlSyntaxColors(
  val comment: Color,
  val key: Color,
  val string: Color,
  val marker: Color,
  val scalar: Color,
)

/**
 * Tokenizes a full YAML document and returns an [AnnotatedString] with span
 * styles applied for comments, map keys, quoted string values, and YAML
 * document markers (`---` / `...`). This is a deliberately light, regex-based
 * pass — full YAML parsing is out of scope for the schema viewer.
 *
 * The function operates per line and preserves byte-for-byte text (including
 * trailing newlines) so the rendered text matches the canonical schema bytes
 * the editor holds in `editor.content`.
 */
internal fun highlightYaml(text: String, colors: YamlSyntaxColors): AnnotatedString {
  val builder = AnnotatedString.Builder(text)
  var cursor = 0
  // Split keeping line content; we re-emit '\n' offsets manually.
  val lines = text.split('\n')
  lines.forEachIndexed { index, line ->
    applyLineSpans(builder, line, cursor, colors)
    cursor += line.length
    if (index != lines.lastIndex) {
      // account for the '\n' we split on
      cursor += 1
    }
  }
  return builder.toAnnotatedString()
}

/**
 * Highlights a single YAML line at the given offset inside [builder].
 *
 * Token rules (in priority order):
 * 1. A line whose first non-whitespace character is `#` is rendered entirely
 *    as a comment span.
 * 2. A line whose trimmed content is exactly `---` or `...` (optionally
 *    followed by a comment) is rendered with the marker color on the marker
 *    token; the rest follows normal rules.
 * 3. A `<key>:` pattern (including dotted/dashed/dollar-prefixed keys, so
 *    `$ref` and `$defs` are naturally captured) at the start of the trimmed
 *    line emits a key span. If the trailing value is a single- or
 *    double-quoted string, that span gets the string color.
 * 4. Anything else is left unstyled (renders in the default text color).
 */
private fun applyLineSpans(builder: AnnotatedString.Builder, line: String, lineStart: Int, colors: YamlSyntaxColors) {
  if (line.isEmpty()) return

  val leadingWhitespace = line.takeWhile { it.isWhitespace() }
  val trimmed = line.substring(leadingWhitespace.length)

  // Rule 1: full-line comment
  if (trimmed.startsWith("#")) {
    builder.addStyle(SpanStyle(color = colors.comment), lineStart, lineStart + line.length)
    return
  }

  // Rule 2: document markers
  if (trimmed == "---" || trimmed == "...") {
    val markerStart = lineStart + leadingWhitespace.length
    builder.addStyle(SpanStyle(color = colors.marker), markerStart, markerStart + 3)
    return
  }
  // marker followed by trailing comment, e.g. "--- # start"
  if ((trimmed.startsWith("--- ") || trimmed.startsWith("... ")) &&
    trimmed.substring(4).trimStart().startsWith("#")
  ) {
    val markerStart = lineStart + leadingWhitespace.length
    builder.addStyle(SpanStyle(color = colors.marker), markerStart, markerStart + 3)
    // comment span covers the rest of the line after the marker+space
    val commentStart = markerStart + 3
    builder.addStyle(SpanStyle(color = colors.comment), commentStart, lineStart + line.length)
    return
  }

  // Rule 3: key: value
  val keyMatch = KEY_REGEX.matchEntire(trimmed)
  if (keyMatch != null) {
    val keyToken = keyMatch.groupValues[1]
    val keyStart = lineStart + leadingWhitespace.length
    val keyEnd = keyStart + keyToken.length
    builder.addStyle(SpanStyle(color = colors.key), keyStart, keyEnd)

    // Value (everything after "key:") — apply string span if it's a quoted
    // scalar. Trailing inline comments are colored too.
    val afterKey = trimmed.substring(keyToken.length + 1) // skip the ':'
    val valueStartInLine = leadingWhitespace.length + keyToken.length + 1
    applyValueSpans(builder, afterKey, lineStart + valueStartInLine, colors)
    return
  }

  // List entries: "- value" or "- key: value". Re-run key matching on the
  // content after the dash so map-style list items (common in schemas, e.g.
  // `- $ref: '#/$defs/...'`) still get keys highlighted.
  if (trimmed.startsWith("- ")) {
    val afterDash = trimmed.substring(2)
    val afterDashStart = lineStart + leadingWhitespace.length + 2
    val nestedKey = KEY_REGEX.matchEntire(afterDash)
    if (nestedKey != null) {
      val keyToken = nestedKey.groupValues[1]
      builder.addStyle(SpanStyle(color = colors.key), afterDashStart, afterDashStart + keyToken.length)
      val afterKey = afterDash.substring(keyToken.length + 1)
      applyValueSpans(builder, afterKey, afterDashStart + keyToken.length + 1, colors)
    } else {
      applyValueSpans(builder, afterDash, afterDashStart, colors)
    }
    return
  }
}

private fun applyValueSpans(
  builder: AnnotatedString.Builder,
  value: String,
  valueStart: Int,
  colors: YamlSyntaxColors,
) {
  if (value.isEmpty()) return
  val leading = value.takeWhile { it.isWhitespace() }
  val rest = value.substring(leading.length)
  if (rest.isEmpty()) return

  // Inline comment at the start of the value (e.g. "key:   # note")
  if (rest.startsWith("#")) {
    val commentStart = valueStart + leading.length
    builder.addStyle(SpanStyle(color = colors.comment), commentStart, valueStart + value.length)
    return
  }

  // Quoted scalar: span the whole quoted literal as a string.
  val quote = rest.firstOrNull()
  if (quote == '"' || quote == '\'') {
    val endRel = findMatchingQuote(rest, quote)
    if (endRel != -1) {
      val stringStart = valueStart + leading.length
      val stringEnd = stringStart + endRel + 1
      builder.addStyle(SpanStyle(color = colors.string), stringStart, stringEnd)
      // optional trailing comment after the closing quote
      val tail = rest.substring(endRel + 1)
      val tailLeading = tail.takeWhile { it.isWhitespace() }
      if (tail.length > tailLeading.length && tail[tailLeading.length] == '#') {
        val commentStart = stringEnd + tailLeading.length
        builder.addStyle(SpanStyle(color = colors.comment), commentStart, valueStart + value.length)
      }
      return
    }
  }

  // Unquoted scalar: split off trailing inline comment, color the scalar.
  val hashIdx = indexOfInlineComment(rest)
  val scalarEndRel = if (hashIdx == -1) rest.length else hashIdx
  val scalarEndTrim = rest.substring(0, scalarEndRel).trimEnd().length
  if (scalarEndTrim > 0) {
    val scalarStart = valueStart + leading.length
    builder.addStyle(SpanStyle(color = colors.scalar), scalarStart, scalarStart + scalarEndTrim)
  }
  if (hashIdx != -1) {
    val commentStart = valueStart + leading.length + hashIdx
    builder.addStyle(SpanStyle(color = colors.comment), commentStart, valueStart + value.length)
  }
}

private fun findMatchingQuote(rest: String, quote: Char): Int {
  // Naive scan; YAML quoting is more involved but this covers the common cases
  // present in the canonical platform-pack schema. Returns the index of the
  // closing quote within [rest], or -1 if not found on the same line.
  var i = 1
  while (i < rest.length) {
    val c = rest[i]
    if (quote == '"' && c == '\\' && i + 1 < rest.length) {
      i += 2
      continue
    }
    if (c == quote) return i
    i++
  }
  return -1
}

private fun indexOfInlineComment(value: String): Int {
  // Look for " #" so we don't split URLs or #-prefixed scalars without a
  // preceding space. Returns the position of '#' on success.
  val idx = value.indexOf(" #")
  return if (idx == -1) -1 else idx + 1
}

private val KEY_REGEX: Regex = Regex("""^([A-Za-z_$][A-Za-z0-9_\-./$]*):(?:\s.*)?$""")

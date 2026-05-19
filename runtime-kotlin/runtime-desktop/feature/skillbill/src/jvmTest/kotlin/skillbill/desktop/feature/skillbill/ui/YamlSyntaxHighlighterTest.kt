package skillbill.desktop.feature.skillbill.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SKILL-47 AC6 — unit coverage for the YAML-aware schema viewer highlighter.
 *
 * The helper is a pure function so we test it directly (no Compose runtime)
 * mirroring the project's state-snapshot test convention. We construct a YAML
 * snippet containing the token shapes the schema viewer needs to color —
 * comment, key, quoted string value, list-of-maps `$ref` entry, document
 * marker — and assert each gets a SpanStyle at the expected offset range with
 * the expected color.
 */
class YamlSyntaxHighlighterTest {

  private val palette = YamlSyntaxColors(
    comment = Color(0xFF111111),
    key = Color(0xFF222222),
    string = Color(0xFF333333),
    marker = Color(0xFF444444),
    scalar = Color(0xFF555555),
  )

  @Test
  fun `highlights comment, key, quoted string, and document marker spans`() {
    val yaml = buildString {
      appendLine("---")
      appendLine("# top-level comment")
      appendLine("kind: \"contract\"")
      appendLine("areas:")
      appendLine("  - \$ref: '#/\$defs/area'")
    }

    val annotated = highlightYaml(yaml, palette)
    val styles = annotated.spanStyles

    assertEquals(yaml, annotated.text, "Highlighter must preserve text byte-for-byte.")
    assertTrue(styles.isNotEmpty(), "Expected at least one span style.")

    // 1) Document marker '---' colored as marker.
    val markerSpan = styles.single { it.start == 0 && it.end == 3 }
    assertEquals(palette.marker, markerSpan.item.color)

    // 2) Comment line '# top-level comment' colored as comment. The comment
    //    line is line 2 — its start offset is len("---\n") = 4.
    val commentStart = 4
    val commentEnd = commentStart + "# top-level comment".length
    val commentSpan = styles.single { it.start == commentStart && it.end == commentEnd }
    assertEquals(palette.comment, commentSpan.item.color)

    // 3) Key 'kind' on line 3, offset = len("---\n# top-level comment\n") = 24.
    val keyLineStart = "---\n# top-level comment\n".length
    val keySpan = styles.single { it.start == keyLineStart && it.end == keyLineStart + 4 }
    assertEquals(palette.key, keySpan.item.color)

    // 4) Quoted string '"contract"' on the same line.
    val quoteStart = keyLineStart + "kind: ".length
    val quoteEnd = quoteStart + "\"contract\"".length
    val stringSpan = styles.single { it.start == quoteStart && it.end == quoteEnd }
    assertEquals(palette.string, stringSpan.item.color)

    // 5) '$ref' key inside the list-of-maps entry on line 5. Offset:
    //    len("---\n# top-level comment\nkind: \"contract\"\nareas:\n  - ").
    val refLinePrefix = "---\n# top-level comment\nkind: \"contract\"\nareas:\n"
    val dashStart = refLinePrefix.length + "  - ".length
    val refSpan = styles.single { it.start == dashStart && it.end == dashStart + "\$ref".length }
    assertEquals(palette.key, refSpan.item.color)

    // 6) The single-quoted YAML pointer '#/$defs/area' is styled as string.
    val pointerStart = dashStart + "\$ref: ".length
    val pointerEnd = pointerStart + "'#/\$defs/area'".length
    val pointerSpan = styles.single { it.start == pointerStart && it.end == pointerEnd }
    assertEquals(palette.string, pointerSpan.item.color)
  }

  @Test
  fun `leaves blank lines and plain text unstyled`() {
    val yaml = "\n\nplain text without colons\n"

    val annotated = highlightYaml(yaml, palette)

    assertEquals(yaml, annotated.text)
    // No tokens of interest — every span list must be empty.
    assertTrue(
      annotated.spanStyles.isEmpty(),
      "Plain text should produce no span styles; got ${annotated.spanStyles}",
    )
  }

  @Test
  fun `unquoted scalar value gets scalar color and trailing comment gets comment color`() {
    val yaml = "version: 1.1 # pin\n"

    val annotated = highlightYaml(yaml, palette)

    val keySpan = annotated.spanStyles.single { it.item.color == palette.key }
    assertEquals(0, keySpan.start)
    assertEquals("version".length, keySpan.end)

    val scalarSpan = annotated.spanStyles.single { it.item.color == palette.scalar }
    val scalarStart = "version: ".length
    assertEquals(scalarStart, scalarSpan.start)
    assertEquals(scalarStart + "1.1".length, scalarSpan.end)

    val commentSpan = annotated.spanStyles.single { it.item.color == palette.comment }
    val commentStart = "version: 1.1 ".length
    assertEquals(commentStart, commentSpan.start)
    // Comment spans through end of line (not the newline).
    assertEquals(commentStart + "# pin".length, commentSpan.end)
  }

  @Test
  fun `splitIntoLines preserves per-line span coloring`() {
    val yaml = "# header\nkind: \"contract\"\n"
    val annotated = highlightYaml(yaml, palette)

    val lines = annotated.splitIntoLines()

    // Expect 3 entries: '# header', 'kind: "contract"', and trailing empty.
    assertEquals(3, lines.size)
    assertEquals("# header", lines[0].text)
    assertEquals("kind: \"contract\"", lines[1].text)
    assertEquals("", lines[2].text)

    val firstLineComment = lines[0].spanStyles.single { it.item == SpanStyle(color = palette.comment) }
    assertEquals(0, firstLineComment.start)
    assertEquals("# header".length, firstLineComment.end)

    val secondLineKey = lines[1].spanStyles.single { it.item.color == palette.key }
    assertEquals(0, secondLineKey.start)
    assertEquals("kind".length, secondLineKey.end)
  }
}

package skillbill.nativeagent

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeAgentSourceParserTest {
  @Test
  fun `compose directive parses governed content target`() {
    val source = parseNativeAgentSourceText(
      "---\n" +
        "name: bill-composed\n" +
        "description: Composed worker.\n" +
        "compose: governed-content\n" +
        "---\n\n",
    )

    assertEquals(
      NativeAgentCompositionDirective(NativeAgentCompositionKind.GovernedContent),
      source.composition,
    )
    assertEquals("", source.body)
  }

  @Test
  fun `renderNativeAgentSource preserves compose directive`() {
    val source = NativeAgentSource(
      name = "bill-composed",
      description = "Composed worker.",
      body = "",
      composition = NativeAgentCompositionDirective(NativeAgentCompositionKind.GovernedContent),
    )

    val expected = "---\n" +
      "name: bill-composed\n" +
      "description: Composed worker.\n" +
      "compose: governed-content\n" +
      "---\n\n\n"
    assertEquals(expected, renderNativeAgentSource(source))
  }

  @Test
  fun `blank body remains rejected without compose directive`() {
    val error = assertFailsWith<IllegalArgumentException> {
      parseNativeAgentSourceText(
        "---\n" +
          "name: bill-blank\n" +
          "description: Blank worker.\n" +
          "---\n\n",
        label = "test source",
      )
    }

    assertContains(error.message.orEmpty(), "test source")
    assertContains(error.message.orEmpty(), "native agent body is required")
  }

  @Test
  fun `unsupported compose directive fails strictly`() {
    val error = assertFailsWith<IllegalArgumentException> {
      parseNativeAgentSourceText(
        "---\n" +
          "name: bill-composed\n" +
          "description: Composed worker.\n" +
          "compose: local-file\n" +
          "---\n\n",
        label = "test source",
      )
    }

    assertContains(error.message.orEmpty(), "test source")
    assertContains(error.message.orEmpty(), "unsupported native agent compose directive 'local-file'")
  }

  @Test
  fun `double-quoted description decodes backslash quote and newline escapes`() {
    val source = parseNativeAgentSourceText(
      """
      ---
      name: bill-quoted-double
      description: "She said \"hi\"\nthen left."
      ---

      # Body
      """.trimIndent(),
    )

    assertEquals("bill-quoted-double", source.name)
    assertEquals("She said \"hi\"\nthen left.", source.description)
  }

  @Test
  fun `single-quoted description decodes doubled apostrophe escape`() {
    val source = parseNativeAgentSourceText(
      """
      ---
      name: bill-quoted-single
      description: 'It''s here'
      ---

      # Body
      """.trimIndent(),
    )

    assertEquals("It's here", source.description)
  }

  @Test
  fun `unquoted description passes through trimmed unchanged`() {
    val source = parseNativeAgentSourceText(
      """
      ---
      name: bill-plain
      description: Plain description with no quoting.
      ---

      # Body
      """.trimIndent(),
    )

    assertEquals("Plain description with no quoting.", source.description)
  }

  @Test
  fun `double-quoted description decodes literal backslash`() {
    val source = parseNativeAgentSourceText(
      """
      ---
      name: bill-backslash
      description: "Path is C:\\Users\\Name"
      ---

      # Body
      """.trimIndent(),
    )

    assertEquals("Path is C:\\Users\\Name", source.description)
  }

  @Test
  fun `unterminated double-quoted description fails`() {
    val text = "---\n" +
      "name: bill-unterminated\n" +
      "description: \"abc\n" +
      "---\n" +
      "\n" +
      "# Body\n"

    val error = assertFailsWith<IllegalArgumentException> {
      parseNativeAgentSourceText(text, label = "test source")
    }

    assertContains(error.message.orEmpty(), "test source")
    assertContains(error.message.orEmpty(), "unterminated double-quoted scalar")
  }

  @Test
  fun `embedded unescaped double quote inside double-quoted description fails`() {
    val text = "---\n" +
      "name: bill-embedded-quote\n" +
      "description: \"foo\"bar\"\n" +
      "---\n" +
      "\n" +
      "# Body\n"

    val error = assertFailsWith<IllegalArgumentException> {
      parseNativeAgentSourceText(text, label = "test source")
    }

    assertContains(error.message.orEmpty(), "test source")
    assertContains(
      error.message.orEmpty(),
      "unescaped double quote inside double-quoted scalar",
    )
  }

  @Test
  fun `unknown backslash escape inside double-quoted description fails`() {
    val text = "---\n" +
      "name: bill-bad-escape\n" +
      "description: \"\\q\"\n" +
      "---\n" +
      "\n" +
      "# Body\n"

    val error = assertFailsWith<IllegalArgumentException> {
      parseNativeAgentSourceText(text, label = "test source")
    }

    assertContains(error.message.orEmpty(), "test source")
    assertContains(error.message.orEmpty(), "unknown escape sequence \\q")
  }

  @Test
  fun `unterminated single-quoted description fails`() {
    val text = "---\n" +
      "name: bill-single-unterminated\n" +
      "description: 'abc\n" +
      "---\n" +
      "\n" +
      "# Body\n"

    val error = assertFailsWith<IllegalArgumentException> {
      parseNativeAgentSourceText(text, label = "test source")
    }

    assertContains(error.message.orEmpty(), "test source")
    assertContains(error.message.orEmpty(), "unterminated single-quoted scalar")
  }
}

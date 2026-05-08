package skillbill.nativeagent

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeAgentSourceParserTest {
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

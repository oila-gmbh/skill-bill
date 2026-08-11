package skillbill.nativeagent

import skillbill.nativeagent.composition.NativeAgentSource
import skillbill.nativeagent.rendering.NativeAgentProvider
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeAgentRenderSnapshotTest {
  private val source = NativeAgentSource(
    name = "bill-snapshot-demo",
    description = "Snapshot demo agent.",
    body = "# Snapshot Demo\n\nFirst line.\nSecond: line with colon.",
  )

  // Description that forces yamlNeedsQuoting + exercises the escape table
  // (contains ": ", a literal newline, and a backslash).
  private val quotedSource = NativeAgentSource(
    name = "bill-snapshot-quoted",
    description = "Edge: case\nwith back\\slash",
    body = "# Snapshot Quoted\n\nBody line.",
  )

  @Test
  fun `claude render is byte-exact`() {
    val expected = """
      ---
      name: bill-snapshot-demo
      description: Snapshot demo agent.
      ---

      # Snapshot Demo

      First line.
      Second: line with colon.

    """.trimIndent()

    assertEquals(expected, NativeAgentProvider.Claude.render(source))
  }

  @Test
  fun `codex render is byte-exact`() {
    val expected = """
      name = "bill-snapshot-demo"
      description = "Snapshot demo agent."

      developer_instructions = ${'"'}${'"'}${'"'}
      # Snapshot Demo

      First line.
      Second: line with colon.
      ${'"'}${'"'}${'"'}

    """.trimIndent()

    assertEquals(expected, NativeAgentProvider.Codex.render(source))
  }

  @Test
  fun `junie render is byte-exact`() {
    val expected = """
      ---
      name: bill-snapshot-demo
      description: Snapshot demo agent.
      ---

      # Snapshot Demo

      First line.
      Second: line with colon.

    """.trimIndent()

    assertEquals(expected, NativeAgentProvider.Junie.render(source))
  }

  @Test
  fun `claude render is byte-exact when description forces yaml quoting`() {
    val expected = """
      ---
      name: bill-snapshot-quoted
      description: "Edge: case\nwith back\\slash"
      ---

      # Snapshot Quoted

      Body line.

    """.trimIndent()

    assertEquals(expected, NativeAgentProvider.Claude.render(quotedSource))
  }

  @Test
  fun `codex render is byte-exact when description has special chars`() {
    val expected = """
      name = "bill-snapshot-quoted"
      description = "Edge: case\nwith back\\slash"

      developer_instructions = ${'"'}${'"'}${'"'}
      # Snapshot Quoted

      Body line.
      ${'"'}${'"'}${'"'}

    """.trimIndent()

    assertEquals(expected, NativeAgentProvider.Codex.render(quotedSource))
  }

  @Test
  fun `junie render is byte-exact when description forces yaml quoting`() {
    val expected = """
      ---
      name: bill-snapshot-quoted
      description: "Edge: case\nwith back\\slash"
      ---

      # Snapshot Quoted

      Body line.

    """.trimIndent()

    assertEquals(expected, NativeAgentProvider.Junie.render(quotedSource))
  }

  @Test
  fun `cursor render is byte-exact`() {
    // Cursor emits name+description only (no tools/model/readonly/is_background).
    val expected = """
      ---
      name: bill-snapshot-demo
      description: Snapshot demo agent.
      ---

      # Snapshot Demo

      First line.
      Second: line with colon.

    """.trimIndent()

    assertEquals(expected, NativeAgentProvider.Cursor.render(source))
    assertEquals("bill-snapshot-demo.md", NativeAgentProvider.Cursor.fileName("bill-snapshot-demo"))
  }

  @Test
  fun `cursor render is byte-exact when description forces yaml quoting`() {
    // Cursor shares yamlScalar with Claude; description line must match and round-trip.
    val expected = """
      ---
      name: bill-snapshot-quoted
      description: "Edge: case\nwith back\\slash"
      ---

      # Snapshot Quoted

      Body line.

    """.trimIndent()

    val cursor = NativeAgentProvider.Cursor.render(quotedSource)
    assertEquals(expected, cursor)
    val claudeDescription = NativeAgentProvider.Claude.render(quotedSource)
      .lines().first { it.startsWith("description: ") }
    val cursorDescription = cursor.lines().first { it.startsWith("description: ") }
    assertEquals(claudeDescription, cursorDescription, "Cursor must reuse shared yamlScalar quoting")
    assertEquals(quotedSource.description, parseFrontmatterDescription(cursorDescription))
  }

  private fun parseFrontmatterDescription(descriptionLine: String): String {
    val raw = descriptionLine.removePrefix("description: ")
    if (raw.startsWith("\"") && raw.endsWith("\"")) {
      val inner = raw.substring(1, raw.length - 1)
      return buildString {
        var index = 0
        while (index < inner.length) {
          val char = inner[index]
          if (char == '\\' && index + 1 < inner.length) {
            when (val next = inner[index + 1]) {
              'n' -> append('\n')
              'r' -> append('\r')
              't' -> append('\t')
              '\\' -> append('\\')
              '"' -> append('"')
              else -> {
                append('\\')
                append(next)
              }
            }
            index += 2
          } else {
            append(char)
            index++
          }
        }
      }
    }
    return raw
  }
}

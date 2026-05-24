package skillbill.nativeagent

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
  fun `opencode render is byte-exact`() {
    val expected = """
      ---
      name: bill-snapshot-demo
      description: Snapshot demo agent.
      mode: subagent
      ---

      # Snapshot Demo

      First line.
      Second: line with colon.

    """.trimIndent()

    assertEquals(expected, NativeAgentProvider.Opencode.render(source))
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
  fun `opencode render is byte-exact when description forces yaml quoting`() {
    val expected = """
      ---
      name: bill-snapshot-quoted
      description: "Edge: case\nwith back\\slash"
      mode: subagent
      ---

      # Snapshot Quoted

      Body line.

    """.trimIndent()

    assertEquals(expected, NativeAgentProvider.Opencode.render(quotedSource))
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
}

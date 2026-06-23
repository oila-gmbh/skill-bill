package skillbill.infrastructure.fs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemFeatureTaskRuntimeSpecStatusWriterTest {
  private val writer = FileSystemFeatureTaskRuntimeSpecStatusWriter()

  @Test
  fun `inserts the Agent line under the Status block adjacent to the Status line`() {
    val spec = writeSpec(
      """
      # SKILL-89 spec

      ## Status

      - Status: Complete
      - Issue: `SKILL-89`

      ## Acceptance Criteria
      1. Criterion one.
      """.trimIndent(),
    )

    writer.writeFinalizingAgent(spec, "claude")

    val lines = Files.readString(spec).lines()
    assertEquals("- Status: Complete", lines.first { it.startsWith("- Status:") })
    assertTrue(lines.contains("- Agent: claude"), "an Agent bullet should follow the Status bullet")
    assertEquals(
      lines.indexOf("- Status: Complete") + 1,
      lines.indexOf("- Agent: claude"),
      "the Agent line is inserted directly after the Status line",
    )
  }

  @Test
  fun `re-running updates the Agent line in place rather than duplicating it`() {
    val spec = writeSpec(
      """
      # SKILL-89 spec

      ## Status

      - Status: Complete

      ## Acceptance Criteria
      1. Criterion one.
      """.trimIndent(),
    )

    writer.writeFinalizingAgent(spec, "codex")
    writer.writeFinalizingAgent(spec, "claude")

    val lines = Files.readString(spec).lines()
    assertEquals(1, lines.count { it.startsWith("- Agent:") }, "exactly one Agent line after a re-run")
    assertTrue(lines.contains("- Agent: claude"), "the line is updated in place to the new agent")
    assertFalse(lines.contains("- Agent: codex"), "the prior agent value is replaced, not duplicated")
  }

  @Test
  fun `re-running with the same agent leaves the file byte-stable`() {
    val spec = writeSpec(
      """
      # SKILL-89 spec

      ## Status

      - Status: Complete

      ## Acceptance Criteria
      1. Criterion one.
      """.trimIndent(),
    )

    writer.writeFinalizingAgent(spec, "claude")
    val afterFirst = Files.readString(spec)
    writer.writeFinalizingAgent(spec, "claude")
    val afterSecond = Files.readString(spec)

    assertEquals(afterFirst, afterSecond)
  }

  @Test
  fun `does not perturb the Acceptance Criteria section`() {
    val spec = writeSpec(
      """
      # SKILL-89 spec

      ## Status

      - Status: Complete

      ## Acceptance Criteria
      1. Criterion one.
      2. Criterion two.
      """.trimIndent(),
    )

    writer.writeFinalizingAgent(spec, "claude")

    val text = Files.readString(spec)
    assertTrue(text.contains("## Acceptance Criteria\n1. Criterion one.\n2. Criterion two."))
    // The Agent line lands before the Acceptance Criteria heading, never inside it.
    val lines = text.lines()
    assertTrue(lines.indexOf("- Agent: claude") < lines.indexOf("## Acceptance Criteria"))
  }

  @Test
  fun `is a no-op when no Status section exists`() {
    val spec = writeSpec(
      """
      # SKILL-89 spec

      ## Acceptance Criteria
      1. Criterion one.
      """.trimIndent(),
    )
    val original = Files.readString(spec)

    writer.writeFinalizingAgent(spec, "claude")

    assertEquals(original, Files.readString(spec))
  }

  @Test
  fun `is a no-op when the spec file is absent`() {
    val absent = Files.createTempDirectory("spec-status-writer").resolve("missing.md")

    writer.writeFinalizingAgent(absent, "claude")

    assertFalse(Files.exists(absent), "the writer must not create a missing spec file")
  }

  private fun writeSpec(text: String): Path =
    Files.createTempDirectory("spec-status-writer").resolve("spec.md").also { path ->
      Files.writeString(path, text)
    }
}

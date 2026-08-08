package skillbill.goalplanning

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoundaryMemoryHeadingParserTest {
  @Test
  fun `history entries parse in file order with stable ids`() {
    val content = """
      # Boundary History — runtime-kotlin/runtime-application

      ## [2026-08-01] planning-heading-walk

      Replaced prefix dumps with a catalog.

      ## [2026-07-02] discovery-exclusions

      Centralized the exclusion contract.
    """.trimIndent()

    val entries = BoundaryMemoryHeadingParser.parse("runtime-kotlin/agent/history.md", content)

    assertEquals(
      listOf("## [2026-08-01] planning-heading-walk", "## [2026-07-02] discovery-exclusions"),
      entries.map(BoundaryMemoryEntry::heading),
    )
    assertEquals(
      entries.map(BoundaryMemoryEntry::headingId),
      BoundaryMemoryHeadingParser.parse("runtime-kotlin/agent/history.md", content)
        .map(BoundaryMemoryEntry::headingId),
    )
    assertTrue(entries.all { entry -> entry.headingId.startsWith("runtime-kotlin/agent/history.md#") })
    assertContains(entries.first().body, "Replaced prefix dumps")
  }

  @Test
  fun `decisions entries parse on the governed title form`() {
    val content = """
      # Boundary Decisions — tooling

      ## [2026-08-01] Bodies are resolved on demand

      Only selected headings are materialized.
    """.trimIndent()

    val entries = BoundaryMemoryHeadingParser.parse("tooling/agent/decisions.md", content)

    assertEquals(listOf("## [2026-08-01] Bodies are resolved on demand"), entries.map(BoundaryMemoryEntry::heading))
  }

  @Test
  fun `malformed regions are skipped without inventing headings or dropping later entries`() {
    val content = """
      # Boundary History — modules/a

      Loose prose before any conforming entry.

      ## [2026-08-01] first-entry

      body one

      ## not a governed heading
      ### [2026-08-01] wrong level

      ## [2026-07-01] second-entry

      body two
    """.trimIndent()

    val entries = BoundaryMemoryHeadingParser.parse("modules/a/agent/history.md", content)

    assertEquals(
      listOf("## [2026-08-01] first-entry", "## [2026-07-01] second-entry"),
      entries.map(BoundaryMemoryEntry::heading),
    )
    assertContains(entries.first().body, "not a governed heading")
  }

  @Test
  fun `a file with no conforming entry yields no headings`() {
    val entries = BoundaryMemoryHeadingParser.parse(
      "modules/a/agent/history.md",
      "# Boundary History\n\nnothing governed here\n",
    )

    assertEquals(emptyList(), entries)
  }

  @Test
  fun `carriage returns normalize before parsing`() {
    val entries = BoundaryMemoryHeadingParser.parse(
      "modules/a/agent/history.md",
      "# H\r\n\r\n## [2026-08-01] crlf-entry\r\n\r\nbody\r\n",
    )

    assertEquals(listOf("## [2026-08-01] crlf-entry"), entries.map(BoundaryMemoryEntry::heading))
    assertEquals("body", entries.single().body)
  }
}

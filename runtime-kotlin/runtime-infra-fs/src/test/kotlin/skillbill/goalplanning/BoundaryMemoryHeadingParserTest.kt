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

  // Both boundary skills mandate newest-entry-first, so a positional id would go stale on every write.
  @Test
  fun `heading ids survive prepending a newer entry`() {
    val path = "modules/a/agent/history.md"
    val before = """
      # Boundary History — modules/a

      ## [2026-08-01] first-entry

      body one

      ## [2026-07-01] second-entry

      body two
    """.trimIndent()
    val after = """
      # Boundary History — modules/a

      ## [2026-09-01] newest-entry

      body zero

      ## [2026-08-01] first-entry

      body one

      ## [2026-07-01] second-entry

      body two
    """.trimIndent()

    val original = BoundaryMemoryHeadingParser.parse(path, before)
    val reparsed = BoundaryMemoryHeadingParser.parse(path, after).associateBy(BoundaryMemoryEntry::headingId)

    assertTrue(original.isNotEmpty())
    original.forEach { entry ->
      val stillThere = reparsed[entry.headingId]
      assertEquals(entry.heading, stillThere?.heading, "id for '${entry.heading}' did not survive a prepend")
      assertEquals(entry.body, stillThere?.body)
    }
  }

  @Test
  fun `identical headings in one file get distinct ids`() {
    val content = """
      # Boundary History — modules/a

      ## [2026-08-01] repeated

      first occurrence

      ## [2026-08-01] repeated

      second occurrence
    """.trimIndent()

    val entries = BoundaryMemoryHeadingParser.parse("modules/a/agent/history.md", content)

    assertEquals(2, entries.size)
    assertEquals(2, entries.map(BoundaryMemoryEntry::headingId).distinct().size)
    assertContains(entries.first().body, "first occurrence")
    assertContains(entries.last().body, "second occurrence")
  }

  // The boundary skills document the entry form with a fenced example, so this is expected content.
  @Test
  fun `a fenced heading inside a body is body text, not a new entry`() {
    val content = """
      # Boundary History — modules/a

      ## [2026-08-01] documents-the-form

      Write entries as:

      ```markdown
      ## [2026-01-01] example-entry
      ```

      Trailing sentence.

      ## [2026-07-01] real-second-entry

      body two
    """.trimIndent()

    val entries = BoundaryMemoryHeadingParser.parse("modules/a/agent/history.md", content)

    assertEquals(
      listOf("## [2026-08-01] documents-the-form", "## [2026-07-01] real-second-entry"),
      entries.map(BoundaryMemoryEntry::heading),
    )
    assertContains(entries.first().body, "## [2026-01-01] example-entry")
    assertContains(entries.first().body, "Trailing sentence.")
  }

  @Test
  fun `a tilde fence is honoured and an unbalanced inner fence does not close it`() {
    val content = """
      # Boundary History — modules/a

      ## [2026-08-01] tilde-fenced

      ~~~
      ## [2026-01-01] not-an-entry
      ~~~

      after
    """.trimIndent()

    val entries = BoundaryMemoryHeadingParser.parse("modules/a/agent/history.md", content)

    assertEquals(listOf("## [2026-08-01] tilde-fenced"), entries.map(BoundaryMemoryEntry::heading))
    assertContains(entries.single().body, "## [2026-01-01] not-an-entry")
  }

  // Newest-first writing means an unclosed fence in the newest entry would otherwise hide the whole
  // history behind it, so the parser rescans without fence tracking rather than losing entries.
  @Test
  fun `an unterminated fence does not swallow the entries behind it`() {
    val content = """
      # Boundary History — modules/a

      ## [2026-08-01] newest-with-broken-fence

      ```kotlin
      val unterminated = true

      ## [2026-07-01] older-entry

      body two
    """.trimIndent()

    val entries = BoundaryMemoryHeadingParser.parse("modules/a/agent/history.md", content)

    assertEquals(
      listOf("## [2026-08-01] newest-with-broken-fence", "## [2026-07-01] older-entry"),
      entries.map(BoundaryMemoryEntry::heading),
      "an unclosed fence must not hide older entries",
    )
    assertContains(entries.last().body, "body two")
  }

  @Test
  fun `a leading byte order mark does not hide the newest entry`() {
    val entries = BoundaryMemoryHeadingParser.parse(
      "modules/a/agent/history.md",
      "\uFEFF## [2026-08-01] newest\n\nbody\n",
    )

    assertEquals(listOf("## [2026-08-01] newest"), entries.map(BoundaryMemoryEntry::heading))
    assertEquals("body", entries.single().body)
  }

  @Test
  fun `a final entry without a trailing newline keeps its body`() {
    val entries = BoundaryMemoryHeadingParser.parse(
      "modules/a/agent/history.md",
      "# H\n\n## [2026-08-01] first\n\nbody one\n\n## [2026-07-01] last\n\nfinal body without newline",
    )

    assertEquals(2, entries.size)
    assertEquals("final body without newline", entries.last().body)
  }
}

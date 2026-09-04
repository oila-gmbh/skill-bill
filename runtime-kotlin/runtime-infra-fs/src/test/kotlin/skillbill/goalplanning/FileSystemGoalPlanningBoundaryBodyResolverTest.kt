package skillbill.goalplanning

import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryBody
import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryHeading
import skillbill.ports.goalrunner.planning.model.GoalPlanningContext
import skillbill.ports.time.JvmSystemClock
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemGoalPlanningBoundaryBodyResolverTest {
  @Test
  fun `selecting a subset returns exactly those bodies`() {
    val repo = twoEntryRepo()
    val catalog = catalogOf(repo)

    val resolved = resolve(repo, listOf(catalog.first().headingId), catalog)

    assertEquals(listOf(catalog.first().headingId), resolved.bodies.map(GoalPlanningBoundaryBody::headingId))
    assertContains(resolved.bodies.single().body, "first body sentence")
    assertFalse(resolved.bodies.any { body -> "second body sentence" in body.body })
    assertTrue(resolved.unresolvedHeadingIds.isEmpty())
    assertFalse(resolved.truncated)
  }

  @Test
  fun `unknown and stale heading ids resolve to nothing`() {
    val repo = twoEntryRepo()
    val relative = "modules/a/agent/history.md"
    val stale = BoundaryMemoryHeadingParser.headingId(relative, "## [2026-01-01] never-existed")
    val catalog = catalogOf(repo)

    // Both ids are admitted to the catalog set so this proves the file re-parse rejects them,
    // not merely that catalog membership does.
    val resolved = resolve(repo, listOf(stale, "not-even-an-id"), catalog, extraIds = setOf(stale, "not-even-an-id"))

    assertTrue(resolved.bodies.isEmpty(), "a stale id never borrows another entry's body")
    assertEquals(listOf(stale, "not-even-an-id"), resolved.unresolvedHeadingIds)
  }

  @Test
  fun `heading ids under an exclusion root resolve to nothing`() {
    val repo = twoEntryRepo()
    val packAgent = Files.createDirectories(repo.resolve("platform-packs/kmp/agent"))
    val packFile = "platform-packs/kmp/agent/history.md"
    val heading = "## [2026-08-01] pack-entry"
    Files.writeString(packAgent.resolve("history.md"), "# Boundary History\n\n$heading\n\npack body\n")
    val packId = BoundaryMemoryHeadingParser.headingId(packFile, heading)

    val resolved = resolve(repo, listOf(packId), catalogOf(repo), extraIds = setOf(packId))

    assertTrue(resolved.bodies.isEmpty())
    assertEquals(listOf(packId), resolved.unresolvedHeadingIds)
  }

  @Test
  fun `an id the catalog never published is not read even when the file exists`() {
    val repo = twoEntryRepo()
    val heading = "## [2026-08-01] private-note"
    Files.createDirectories(repo.resolve("docs"))
    Files.writeString(repo.resolve("docs/notes.md"), "# Notes\n\n$heading\n\nsecret note body\n")
    val forged = BoundaryMemoryHeadingParser.headingId("docs/notes.md", heading)

    val offCatalog = resolve(repo, listOf(forged), catalogOf(repo))
    assertTrue(offCatalog.bodies.isEmpty(), "an off-catalog id must not reach the filesystem")
    assertEquals(listOf(forged), offCatalog.unresolvedHeadingIds)

    // Even if the catalog itself were forged, the path is not governed boundary memory.
    val forgedCatalog = resolve(repo, listOf(forged), catalogOf(repo), extraIds = setOf(forged))
    assertTrue(forgedCatalog.bodies.isEmpty(), "only <dir>/agent/{history,decisions}.md is boundary memory")
    assertEquals(listOf(forged), forgedCatalog.unresolvedHeadingIds)
  }

  @Test
  fun `a traversal shaped heading id is denied`() {
    val repo = twoEntryRepo()
    val outside = Files.createDirectories(repo.parent.resolve("outside-${repo.fileName}/agent"))
    val heading = "## [2026-08-01] outside-entry"
    Files.writeString(outside.resolve("history.md"), "# Boundary History\n\n$heading\n\noutside body\n")
    val escaping = BoundaryMemoryHeadingParser.headingId("../outside-${repo.fileName}/agent/history.md", heading)

    val resolved = resolve(repo, listOf(escaping), catalogOf(repo), extraIds = setOf(escaping))

    assertTrue(resolved.bodies.isEmpty(), "containment must hold even for a catalog-admitted id")
    assertEquals(listOf(escaping), resolved.unresolvedHeadingIds)
  }

  @Test
  fun `body caps truncate deterministically`() {
    val repo = Files.createTempDirectory("goal-body-caps")
    val agent = Files.createDirectories(repo.resolve("modules/big/agent"))
    val oversized = "x".repeat(GoalPlanningContext.MAX_BODY_BYTES + 1_000)
    Files.writeString(agent.resolve("history.md"), "# Boundary History\n\n## [2026-08-01] big\n\n$oversized\n")
    val catalog = catalogOf(repo)

    val resolved = resolve(repo, catalog.map(GoalPlanningBoundaryHeading::headingId), catalog)

    assertEquals(GoalPlanningContext.MAX_BODY_BYTES, resolved.bodies.single().body.length)
    assertTrue(resolved.truncated)
    assertEquals(resolved, resolve(repo, catalog.map(GoalPlanningBoundaryHeading::headingId), catalog))
  }

  @Test
  fun `the body cap is measured in utf8 bytes and never splits a code point`() {
    val repo = Files.createTempDirectory("goal-body-utf8")
    val agent = Files.createDirectories(repo.resolve("modules/utf8/agent"))
    val oversized = "あ".repeat(GoalPlanningContext.MAX_BODY_BYTES)
    Files.writeString(agent.resolve("history.md"), "# Boundary History\n\n## [2026-08-01] utf8\n\n$oversized\n")
    val catalog = catalogOf(repo)

    val body = resolve(repo, catalog.map(GoalPlanningBoundaryHeading::headingId), catalog).bodies.single().body

    assertTrue(body.toByteArray(Charsets.UTF_8).size <= GoalPlanningContext.MAX_BODY_BYTES)
    assertTrue(body.isNotEmpty())
    assertFalse(body.last().isSurrogate(), "truncation must land on a code-point boundary")
    assertTrue(body.all { char -> char == 'あ' }, "no partial code point may reach the prompt")
  }

  @Test
  fun `selections beyond the aggregate cap are reported unresolved rather than dropped`() {
    val repo = Files.createTempDirectory("goal-body-aggregate")
    val agent = Files.createDirectories(repo.resolve("modules/many/agent"))
    val entries = (1..GoalPlanningContext.MAX_SELECTED_BODIES + 6).joinToString("\n") { index ->
      "## [2026-08-%02d] entry-$index\n\nbody $index\n".format(index)
    }
    Files.writeString(agent.resolve("history.md"), "# Boundary History\n\n$entries")
    val catalog = catalogOf(repo)
    val selected = catalog.map(GoalPlanningBoundaryHeading::headingId)
    assertEquals(GoalPlanningContext.MAX_SELECTED_BODIES + 6, selected.size)

    val resolved = resolve(repo, selected, catalog)

    assertEquals(GoalPlanningContext.MAX_SELECTED_BODIES, resolved.bodies.size)
    assertTrue(resolved.truncated)
    assertEquals(
      selected.drop(GoalPlanningContext.MAX_SELECTED_BODIES),
      resolved.unresolvedHeadingIds,
      "every selection past the cap must appear in unresolvedHeadingIds",
    )
    assertEquals(
      selected.toSet(),
      (resolved.bodies.map(GoalPlanningBoundaryBody::headingId) + resolved.unresolvedHeadingIds).toSet(),
      "bodies plus unresolved must account for the whole selection",
    )
  }

  private fun catalogOf(repo: Path): List<GoalPlanningBoundaryHeading> =
    FileSystemGoalPlanningContextDiscovery(JvmSystemClock).discover(repo).boundaryCatalog

  private fun resolve(
    repo: Path,
    headingIds: List<String>,
    catalog: List<GoalPlanningBoundaryHeading>,
    extraIds: Set<String> = emptySet(),
  ) = FileSystemGoalPlanningBoundaryBodyResolver()
    .resolve(repo, headingIds, catalog.map(GoalPlanningBoundaryHeading::headingId).toSet() + extraIds)

  private fun twoEntryRepo(): Path {
    val repo = Files.createTempDirectory("goal-body-resolver")
    val agent = Files.createDirectories(repo.resolve("modules/a/agent"))
    Files.writeString(
      agent.resolve("history.md"),
      """
      # Boundary History — modules/a

      ## [2026-08-01] first-entry

      first body sentence

      ## [2026-07-01] second-entry

      second body sentence
      """.trimIndent() + "\n",
    )
    return repo
  }
}

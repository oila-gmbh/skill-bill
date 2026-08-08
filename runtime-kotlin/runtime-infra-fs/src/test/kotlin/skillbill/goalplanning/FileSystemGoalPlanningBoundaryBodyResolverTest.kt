package skillbill.goalplanning

import skillbill.ports.goalrunner.model.GoalPlanningBoundaryBody
import skillbill.ports.goalrunner.model.GoalPlanningContext
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
    val catalog = FileSystemGoalPlanningContextDiscovery().discover(repo).boundaryCatalog

    val resolved = FileSystemGoalPlanningBoundaryBodyResolver()
      .resolve(repo, listOf(catalog.first().headingId))

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
    val stale = BoundaryMemoryHeadingParser.headingId(relative, 0, "## [2026-01-01] never-existed")

    val resolved = FileSystemGoalPlanningBoundaryBodyResolver()
      .resolve(repo, listOf(stale, "not-even-an-id"))

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

    val resolved = FileSystemGoalPlanningBoundaryBodyResolver()
      .resolve(repo, listOf(BoundaryMemoryHeadingParser.headingId(packFile, 0, heading)))

    assertTrue(resolved.bodies.isEmpty())
    assertEquals(1, resolved.unresolvedHeadingIds.size)
  }

  @Test
  fun `body caps truncate deterministically`() {
    val repo = Files.createTempDirectory("goal-body-caps")
    val agent = Files.createDirectories(repo.resolve("modules/big/agent"))
    val oversized = "x".repeat(GoalPlanningContext.MAX_BODY_BYTES + 1_000)
    Files.writeString(agent.resolve("history.md"), "# Boundary History\n\n## [2026-08-01] big\n\n$oversized\n")
    val catalog = FileSystemGoalPlanningContextDiscovery().discover(repo).boundaryCatalog

    val resolver = FileSystemGoalPlanningBoundaryBodyResolver()
    val resolved = resolver.resolve(repo, catalog.map { it.headingId })

    assertEquals(GoalPlanningContext.MAX_BODY_BYTES, resolved.bodies.single().body.length)
    assertTrue(resolved.truncated)
    assertEquals(resolved, resolver.resolve(repo, catalog.map { it.headingId }))
  }

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

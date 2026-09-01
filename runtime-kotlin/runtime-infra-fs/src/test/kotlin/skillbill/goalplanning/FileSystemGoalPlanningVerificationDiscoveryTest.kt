package skillbill.goalplanning

import skillbill.contracts.goalplanning.GoalPlanningDiscoveryExclusions
import skillbill.contracts.goalplanning.GoalVerificationBoundaryCaps
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemGoalPlanningVerificationDiscoveryTest {
  @Test
  fun `finding path catalogs only owning boundaries`() {
    val repo = Files.createTempDirectory("goal-verification-scope-owning")
    val ownerAgent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
    writeEntries(ownerAgent.resolve("history.md"), "owner-history", "owner body")
    val otherAgent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-domain/agent"))
    writeEntries(otherAgent.resolve("history.md"), "other-history", "other body")

    val discovery = FileSystemGoalPlanningContextDiscovery().discoverForFindingPaths(
      repo,
      listOf("runtime-kotlin/runtime-application/src/Foo.kt"),
    )

    assertFalse(discovery.boundaryContextUnavailable)
    assertEquals(
      listOf("runtime-kotlin/runtime-application/agent/history.md"),
      discovery.boundaryCatalog.map {
        it.sourcePath
      },
    )
    assertFalse(discovery.boundaryCatalog.any { "other body" in it.heading || "other-history" in it.heading })
  }

  @Test
  fun `excluded root contributes zero headings`() {
    val repo = Files.createTempDirectory("goal-verification-scope-excluded")
    val packAgent = Files.createDirectories(repo.resolve("platform-packs/kmp/agent"))
    writeEntries(packAgent.resolve("history.md"), "pack-history", "pack body")
    val ownerAgent = Files.createDirectories(repo.resolve("modules/safe/agent"))
    writeEntries(ownerAgent.resolve("history.md"), "safe-history", "safe body")

    val fromPackPath = FileSystemGoalPlanningContextDiscovery().discoverForFindingPaths(
      repo,
      listOf("platform-packs/kmp/content.md"),
    )
    val fromSafePath = FileSystemGoalPlanningContextDiscovery().discoverForFindingPaths(
      repo,
      listOf("modules/safe/src/Main.kt"),
    )

    assertTrue(fromPackPath.boundaryContextUnavailable)
    assertTrue(fromPackPath.boundaryCatalog.isEmpty())
    assertFalse(fromSafePath.boundaryContextUnavailable)
    assertEquals(listOf("modules/safe/agent/history.md"), fromSafePath.boundaryCatalog.map { it.sourcePath })
    assertTrue(
      fromSafePath.boundaryCatalog.none { entry -> GoalPlanningDiscoveryExclusions.isExcluded(entry.sourcePath) },
    )
  }

  @Test
  fun `no eligible boundary marks context unavailable`() {
    val repo = Files.createTempDirectory("goal-verification-scope-none")
    val discovery = FileSystemGoalPlanningContextDiscovery().discoverForFindingPaths(
      repo,
      listOf("FeatureTaskRuntimeRunLoop.kt"),
    )
    assertTrue(discovery.boundaryContextUnavailable)
    assertTrue(discovery.boundaryCatalog.isEmpty())
  }

  @Test
  fun `absolute and traversal finding paths do not widen boundary discovery`() {
    val repo = Files.createTempDirectory("goal-verification-bad-paths")
    val ownerAgent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
    writeEntries(ownerAgent.resolve("history.md"), "owner-history", "owner body")
    val discovery = FileSystemGoalPlanningContextDiscovery()

    val fromAbsolute = discovery.discoverForFindingPaths(
      repo,
      listOf("/runtime-kotlin/runtime-application/src/Foo.kt"),
    )
    val fromTraversal = discovery.discoverForFindingPaths(
      repo,
      listOf("../runtime-kotlin/runtime-application/src/Foo.kt"),
    )

    assertTrue(fromAbsolute.boundaryContextUnavailable)
    assertTrue(fromAbsolute.boundaryCatalog.isEmpty())
    assertTrue(fromTraversal.boundaryContextUnavailable)
    assertTrue(fromTraversal.boundaryCatalog.isEmpty())

    val fromBackslashTraversal = discovery.discoverForFindingPaths(
      repo,
      listOf("runtime-kotlin\\runtime-application\\..\\src\\Foo.kt"),
    )
    assertTrue(fromBackslashTraversal.boundaryContextUnavailable)
    assertTrue(fromBackslashTraversal.boundaryCatalog.isEmpty())
  }

  @Test
  fun `verification discovery filters history entries older than the configured recency window`() {
    val repo = Files.createTempDirectory("goal-verification-history-recency")
    val agent = Files.createDirectories(repo.resolve("modules/a/agent"))
    val recent = LocalDate.now(ZoneOffset.UTC).minusDays(5)
    val stale = LocalDate.now(ZoneOffset.UTC).minusDays(GoalVerificationBoundaryCaps.historyRecencyDays + 1L)
    Files.writeString(
      agent.resolve("history.md"),
      """
      # Boundary History

      ## [$recent] recent-entry

      recent body

      ## [$stale] stale-entry

      stale body
      """.trimIndent() + "\n",
    )

    val discovery = FileSystemGoalPlanningContextDiscovery().discoverForFindingPaths(
      repo,
      listOf("modules/a/src/Main.kt"),
    )

    assertFalse(discovery.boundaryContextUnavailable)
    assertEquals(1, discovery.boundaryCatalog.size)
    assertTrue(discovery.boundaryCatalog.single().heading.contains("recent-entry"))
    assertTrue(discovery.boundaryCatalogTruncated)
  }

  @Test
  fun `verification discovery does not filter decisions by history recency`() {
    val repo = Files.createTempDirectory("goal-verification-decisions-recency")
    val agent = Files.createDirectories(repo.resolve("modules/a/agent"))
    val stale = LocalDate.now(ZoneOffset.UTC).minusDays(GoalVerificationBoundaryCaps.historyRecencyDays + 1L)
    Files.writeString(
      agent.resolve("decisions.md"),
      "# Boundary Decisions\n\n## [$stale] stale-decision\n\nstale decision body\n",
    )

    val discovery = FileSystemGoalPlanningContextDiscovery().discoverForFindingPaths(
      repo,
      listOf("modules/a/src/Main.kt"),
    )

    assertFalse(discovery.boundaryContextUnavailable)
    assertEquals(1, discovery.boundaryCatalog.size)
    assertTrue(discovery.boundaryCatalog.single().heading.contains("stale-decision"))
    assertFalse(discovery.boundaryCatalogTruncated)
  }

  private fun writeEntries(file: Path, title: String, body: String) {
    val recent = LocalDate.now(ZoneOffset.UTC).minusDays(5)
    Files.writeString(
      file,
      "# Boundary History\n\n## [$recent] $title\n\n$body\n",
    )
  }
}

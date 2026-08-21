package skillbill.goalplanning

import skillbill.contracts.goalplanning.GoalPlanningDiscoveryExclusions
import java.nio.file.Files
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

  private fun writeEntries(file: java.nio.file.Path, title: String, body: String) {
    Files.writeString(
      file,
      "# Boundary History\n\n## [2026-08-01] $title\n\n$body\n",
    )
  }
}

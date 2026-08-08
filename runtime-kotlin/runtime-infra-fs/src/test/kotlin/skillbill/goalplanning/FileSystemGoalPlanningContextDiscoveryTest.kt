package skillbill.goalplanning

import org.junit.jupiter.api.Assumptions.assumeTrue
import skillbill.contracts.goalplanning.GoalPlanningDiscoveryExclusions
import skillbill.ports.goalrunner.model.GoalPlanningBoundaryHeading
import skillbill.ports.goalrunner.model.GoalPlanningContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemGoalPlanningContextDiscoveryTest {
  @Test
  fun `catalog carries headings only and never entry bodies`() {
    val repo = Files.createTempDirectory("goal-context-catalog")
    val moduleAgent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
    writeEntries(moduleAgent.resolve("history.md"), "module-history", "distinctive history body sentence")
    writeEntries(moduleAgent.resolve("decisions.md"), "module-decision", "distinctive decision body sentence")
    Files.writeString(repo.resolve("AGENTS.md"), "repo conventions for planning")

    val context = FileSystemGoalPlanningContextDiscovery().discover(repo)

    assertEquals(
      listOf(
        "runtime-kotlin/runtime-application/agent/history.md",
        "runtime-kotlin/runtime-application/agent/decisions.md",
      ),
      context.boundaryCatalog.map(GoalPlanningBoundaryHeading::sourcePath),
    )
    assertEquals(
      listOf(GoalPlanningContext.KIND_HISTORY, GoalPlanningContext.KIND_DECISIONS),
      context.boundaryCatalog.map(GoalPlanningBoundaryHeading::kind),
    )
    assertContains(context.boundaryCatalog.first().heading, "module-history")
    assertFalse(
      context.boundaryCatalog.any { entry -> "distinctive" in entry.heading || "body sentence" in entry.heading },
      "catalog payload is heading text only",
    )
    assertFalse(context.boundaryCatalogTruncated)
    assertContains(context.validationGuidance, "repo conventions for planning")
  }

  @Test
  fun `platform pack agent trees contribute zero catalog entries`() {
    val repo = Files.createTempDirectory("goal-context-exclusions")
    val packAgent = Files.createDirectories(repo.resolve("platform-packs/kmp/agent"))
    writeEntries(packAgent.resolve("history.md"), "pack-history", "pack history must not be discovered")
    writeEntries(packAgent.resolve("decisions.md"), "pack-decision", "pack decisions must not be discovered")
    val moduleAgent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
    writeEntries(moduleAgent.resolve("history.md"), "module-history", "module body")

    val context = FileSystemGoalPlanningContextDiscovery().discover(repo)

    assertEquals(1, context.boundaryCatalog.size)
    assertTrue(
      context.boundaryCatalog.none { entry ->
        GoalPlanningDiscoveryExclusions.isExcluded(entry.sourcePath) ||
          GoalPlanningDiscoveryExclusions.excludedRoots.any { root -> entry.headingId.startsWith(root) }
      },
      "no catalog entry names an exclusion-list root in its source path or heading id",
    )
    assertFalse(context.boundaryCatalog.any { entry -> "pack-" in entry.heading })
  }

  @Test
  fun `every excluded root and directory name is pruned at any depth`() {
    val repo = Files.createTempDirectory("goal-context-all-roots")
    GoalPlanningDiscoveryExclusions.excludedRoots.forEach { root ->
      val agent = Files.createDirectories(repo.resolve(root).resolve("nested/agent"))
      writeEntries(agent.resolve("history.md"), "excluded-history", "excluded body")
    }
    GoalPlanningDiscoveryExclusions.excludedDirectoryNames.forEach { name ->
      // nested, not repo-root: an anchored-prefix-only gate would walk straight into these
      val agent = Files.createDirectories(repo.resolve("runtime-kotlin/module/$name/nested/agent"))
      writeEntries(agent.resolve("history.md"), "excluded-history", "excluded body")
    }
    val moduleAgent = Files.createDirectories(repo.resolve("tooling/agent"))
    writeEntries(moduleAgent.resolve("history.md"), "tooling-history", "tooling body")

    val context = FileSystemGoalPlanningContextDiscovery().discover(repo)

    assertEquals(listOf("tooling/agent/history.md"), context.boundaryCatalog.map { it.sourcePath })
    assertFalse(context.boundaryCatalog.any { entry -> "excluded-history" in entry.heading })
  }

  @Test
  fun `symlinks into an excluded root stay denied after canonicalization`() {
    val repo = Files.createTempDirectory("goal-context-symlink-exclusion")
    val packAgent = Files.createDirectories(repo.resolve("platform-packs/kmp/agent"))
    val packHistory = writeEntries(packAgent.resolve("history.md"), "pack-history", "pack body")
    val outside = Files.createTempDirectory("goal-context-outside")
    val outsideAgent = Files.createDirectories(outside.resolve("agent"))
    writeEntries(outsideAgent.resolve("history.md"), "outside-history", "outside body")
    val safeAgent = Files.createDirectories(repo.resolve("modules/safe/agent"))
    writeEntries(safeAgent.resolve("history.md"), "safe-history", "safe body")

    val linkable = runCatching {
      Files.createSymbolicLink(repo.resolve("modules/linked-dir"), packAgent.parent)
      Files.createDirectories(repo.resolve("modules/linked-file/agent"))
      Files.createSymbolicLink(repo.resolve("modules/linked-file/agent/history.md"), packHistory)
      Files.createSymbolicLink(repo.resolve("modules/escaped"), outside)
    }.isSuccess
    assumeTrue(linkable, "filesystem cannot create symbolic links")

    val context = FileSystemGoalPlanningContextDiscovery().discover(repo)

    assertEquals(listOf("modules/safe/agent/history.md"), context.boundaryCatalog.map { it.sourcePath })
    assertFalse(context.boundaryCatalog.any { entry -> "pack-history" in entry.heading })
    assertFalse(context.boundaryCatalog.any { entry -> "outside-history" in entry.heading })
  }

  @Test
  fun `eligible file and total heading caps truncate at a deterministic boundary`() {
    assertEquals(32, GoalPlanningContext.MAX_DISCOVERY_FILE_COUNT)
    assertEquals(256, GoalPlanningContext.MAX_CATALOG_HEADINGS)

    val repo = Files.createTempDirectory("goal-context-bounds")
    repeat(40) { index ->
      val agent = Files.createDirectories(repo.resolve("modules/module-%02d/agent".format(index)))
      writeEntries(agent.resolve("history.md"), "history-$index", "body")
      writeEntries(agent.resolve("decisions.md"), "decisions-$index", "body")
    }

    val discovery = FileSystemGoalPlanningContextDiscovery()
    val context = discovery.discover(repo)

    assertTrue(context.boundaryCatalogTruncated)
    assertEquals(
      (0 until 16).flatMap { index ->
        listOf(
          "modules/module-%02d/agent/history.md".format(index),
          "modules/module-%02d/agent/decisions.md".format(index),
        )
      },
      context.boundaryCatalog.map(GoalPlanningBoundaryHeading::sourcePath),
    )
    assertEquals(
      context.boundaryCatalog,
      discovery.discover(repo).boundaryCatalog,
      "the same fixture truncates identically across repeated runs",
    )
  }

  @Test
  fun `per file heading cap truncates and marks the catalog`() {
    val repo = Files.createTempDirectory("goal-context-per-file-cap")
    val agent = Files.createDirectories(repo.resolve("modules/big/agent"))
    val entries = (0 until GoalPlanningContext.MAX_HEADINGS_PER_FILE + 5).joinToString("\n\n") { index ->
      "## [2026-08-%02d] entry-$index\n\nbody $index".format((index % 28) + 1)
    }
    Files.writeString(agent.resolve("history.md"), "# Boundary History\n\n$entries\n")

    val context = FileSystemGoalPlanningContextDiscovery().discover(repo)

    assertEquals(GoalPlanningContext.MAX_HEADINGS_PER_FILE, context.boundaryCatalog.size)
    assertTrue(context.boundaryCatalogTruncated)
  }

  private fun writeEntries(path: Path, title: String, body: String): Path =
    Files.writeString(path, "# Boundary History\n\n## [2026-08-01] $title\n\n$body\n")
}

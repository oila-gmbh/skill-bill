package skillbill.goalplanning

import org.junit.jupiter.api.Assumptions.assumeTrue
import skillbill.contracts.goalplanning.GoalPlanningDiscoveryExclusions
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

class FileSystemGoalPlanningContextDiscoveryTest {
  @Test
  fun `catalog carries headings only and never entry bodies`() {
    val repo = Files.createTempDirectory("goal-context-catalog")
    val moduleAgent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
    writeEntries(moduleAgent.resolve("history.md"), "module-history", "distinctive history body sentence")
    writeEntries(moduleAgent.resolve("decisions.md"), "module-decision", "distinctive decision body sentence")
    Files.writeString(repo.resolve("AGENTS.md"), "repo conventions for planning")

    val context = FileSystemGoalPlanningContextDiscovery(JvmSystemClock).discover(repo)

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

    val context = FileSystemGoalPlanningContextDiscovery(JvmSystemClock).discover(repo)

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

    val context = FileSystemGoalPlanningContextDiscovery(JvmSystemClock).discover(repo)

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

    val context = FileSystemGoalPlanningContextDiscovery(JvmSystemClock).discover(repo)

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

    val discovery = FileSystemGoalPlanningContextDiscovery(JvmSystemClock)
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

    val context = FileSystemGoalPlanningContextDiscovery(JvmSystemClock).discover(repo)

    assertEquals(GoalPlanningContext.MAX_HEADINGS_PER_FILE, context.boundaryCatalog.size)
    assertTrue(context.boundaryCatalogTruncated)
  }

  // Under a sequential take the alphabetically-first modules consume every slot and the rest of the
  // repository is invisible to planning.
  @Test
  fun `a large early module cannot starve later modules out of the catalog`() {
    val repo = Files.createTempDirectory("goal-context-fairness")
    val modules = (0 until 6).map { index -> "modules/module-%02d".format(index) }
    modules.forEach { module ->
      val agent = Files.createDirectories(repo.resolve("$module/agent"))
      val entries = (0 until GoalPlanningContext.MAX_HEADINGS_PER_FILE).joinToString("\n\n") { index ->
        "## [2026-08-%02d] ${module.substringAfterLast('/')}-entry-$index\n\nbody $index".format((index % 28) + 1)
      }
      Files.writeString(agent.resolve("history.md"), "# Boundary History\n\n$entries\n")
    }

    val context = FileSystemGoalPlanningContextDiscovery(JvmSystemClock).discover(repo)

    assertEquals(GoalPlanningContext.MAX_CATALOG_HEADINGS, context.boundaryCatalog.size)
    assertTrue(context.boundaryCatalogTruncated)
    assertEquals(
      modules.map { module -> "$module/agent/history.md" },
      context.boundaryCatalog.map(GoalPlanningBoundaryHeading::sourcePath).distinct(),
      "every module must be represented, and in discovery order",
    )
    val perModule = context.boundaryCatalog.groupingBy(GoalPlanningBoundaryHeading::sourcePath).eachCount()
    assertTrue(
      perModule.values.max() - perModule.values.min() <= 1,
      "the cap is shared evenly, not spent on whichever module sorts first: $perModule",
    )
  }

  // validation_guidance rides into every planning prompt, so an unbounded AGENTS.md rides with it.
  @Test
  fun `validation guidance is bounded by its declared cap`() {
    val repo = Files.createTempDirectory("goal-context-guidance-cap")
    Files.createDirectories(repo.resolve("modules/a/agent"))
    writeEntries(repo.resolve("modules/a/agent/history.md"), "history", "body")
    Files.writeString(repo.resolve("AGENTS.md"), "g".repeat(GoalPlanningContext.MAX_VALIDATION_GUIDANCE_BYTES * 3))

    val context = FileSystemGoalPlanningContextDiscovery(JvmSystemClock).discover(repo)

    assertEquals(GoalPlanningContext.MAX_VALIDATION_GUIDANCE_BYTES, context.validationGuidance.length)
  }

  @Test
  fun `heading text is truncated at its declared cap`() {
    val repo = Files.createTempDirectory("goal-context-heading-cap")
    val agent = Files.createDirectories(repo.resolve("modules/a/agent"))
    val longTitle = "t".repeat(GoalPlanningContext.MAX_HEADING_TEXT_CHARS * 2)
    Files.writeString(agent.resolve("history.md"), "# Boundary History\n\n## [2026-08-01] $longTitle\n\nbody\n")

    val context = FileSystemGoalPlanningContextDiscovery(JvmSystemClock).discover(repo)

    assertEquals(GoalPlanningContext.MAX_HEADING_TEXT_CHARS, context.boundaryCatalog.single().heading.length)
  }

  // A present-but-unreadable file is not an absent one; skipping it silently claims false completeness.
  @Test
  fun `an unreadable boundary file marks the catalog truncated instead of vanishing`() {
    val repo = Files.createTempDirectory("goal-context-unreadable")
    val readable = Files.createDirectories(repo.resolve("modules/readable/agent"))
    writeEntries(readable.resolve("history.md"), "readable-history", "readable body")
    val blocked = Files.createDirectories(repo.resolve("modules/blocked/agent"))
    val blockedFile = writeEntries(blocked.resolve("history.md"), "blocked-history", "blocked body")
    val denied = runCatching {
      Files.setPosixFilePermissions(blockedFile, emptySet())
      !Files.isReadable(blockedFile)
    }.getOrDefault(false)
    assumeTrue(denied, "filesystem cannot make a file unreadable for this user")

    val context = FileSystemGoalPlanningContextDiscovery(JvmSystemClock).discover(repo)

    assertEquals(
      listOf("modules/readable/agent/history.md"),
      context.boundaryCatalog.map(GoalPlanningBoundaryHeading::sourcePath),
    )
    assertTrue(context.boundaryCatalogTruncated, "an unreadable file must not read as a complete catalog")
  }

  // Two different read caps let the passes parse different text and produce disagreeing digests.
  @Test
  fun `discovery and body resolution agree on heading ids for the same file`() {
    val repo = Files.createTempDirectory("goal-context-read-parity")
    val agent = Files.createDirectories(repo.resolve("modules/a/agent"))
    val entries = (0 until 20).joinToString("\n\n") { index ->
      "## [2026-08-%02d] entry-$index\n\n${"filler ".repeat(200)}body $index".format((index % 28) + 1)
    }
    Files.writeString(agent.resolve("history.md"), "# Boundary History\n\n$entries\n")

    val catalog = FileSystemGoalPlanningContextDiscovery(JvmSystemClock).discover(repo).boundaryCatalog
    val ids = catalog.map(GoalPlanningBoundaryHeading::headingId)
    val resolved = FileSystemGoalPlanningBoundaryBodyResolver().resolve(repo, ids.take(5), ids.toSet())

    assertEquals(ids.take(5), resolved.bodies.map { body -> body.headingId })
    assertTrue(resolved.unresolvedHeadingIds.isEmpty(), "a catalog id must always resolve against the same read")
  }

  // A file cut at the per-file read cap loses every entry past the cut; reporting completeness there
  // is the same silent loss as skipping an unreadable file.
  @Test
  fun `a boundary file larger than the per file cap marks the catalog truncated`() {
    val repo = Files.createTempDirectory("goal-context-file-cap")
    val agent = Files.createDirectories(repo.resolve("modules/huge/agent"))
    val filler = "f".repeat(4_096)
    val entries = (0 until 64).joinToString("\n\n") { index ->
      "## [2026-08-%02d] entry-$index\n\n$filler".format((index % 28) + 1)
    }
    Files.writeString(agent.resolve("history.md"), "# Boundary History\n\n$entries\n")
    assertTrue(
      Files.size(agent.resolve("history.md")) > GoalPlanningContext.MAX_BOUNDARY_FILE_BYTES,
      "fixture must exceed the per-file read cap",
    )

    val context = FileSystemGoalPlanningContextDiscovery(JvmSystemClock).discover(repo)

    assertTrue(context.boundaryCatalog.isNotEmpty(), "the readable prefix still contributes headings")
    assertTrue(context.boundaryCatalogTruncated, "a cut file must not read as a complete catalog")
  }

  private fun writeEntries(path: Path, title: String, body: String): Path =
    Files.writeString(path, "# Boundary History\n\n## [2026-08-01] $title\n\n$body\n")
}

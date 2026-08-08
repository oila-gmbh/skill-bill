package skillbill.goalplanning

import org.junit.jupiter.api.Assumptions.assumeTrue
import skillbill.contracts.goalplanning.GoalPlanningDiscoveryExclusions
import skillbill.ports.goalrunner.model.GoalPlanningContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemGoalPlanningContextDiscoveryTest {
  @Test
  fun `platform pack agent trees contribute nothing while non excluded module memory survives`() {
    val repo = Files.createTempDirectory("goal-context-exclusions")
    val packAgent = Files.createDirectories(repo.resolve("platform-packs/kmp/agent"))
    Files.writeString(packAgent.resolve("history.md"), "pack history must not be discovered")
    Files.writeString(packAgent.resolve("decisions.md"), "pack decisions must not be discovered")
    Files.writeString(repo.resolve("platform-packs/kmp/platform.yaml"), "routing_signals: [kmp]")
    val moduleAgent = Files.createDirectories(repo.resolve("runtime-kotlin/runtime-application/agent"))
    Files.writeString(moduleAgent.resolve("history.md"), "module history")
    Files.writeString(moduleAgent.resolve("decisions.md"), "module decision")
    Files.writeString(repo.resolve("AGENTS.md"), "repo conventions for planning")

    val context = FileSystemGoalPlanningContextDiscovery().discover(repo)

    assertEquals(
      listOf(
        "runtime-kotlin/runtime-application/agent/history.md",
        "runtime-kotlin/runtime-application/agent/decisions.md",
      ),
      context.boundaryMemory.keys.toList(),
    )
    assertContains(context.boundaryMemory.getValue("runtime-kotlin/runtime-application/agent/history.md"), "module")
    assertFalse(context.boundaryMemory.keys.any { path -> path.startsWith("platform-packs/") })
    assertFalse(context.boundaryMemory.values.any { value -> "must not be discovered" in value })
    assertFalse(context.boundaryMemory.keys.any { path -> path.contains("platform.yaml") })
    assertContains(context.validationGuidance, "repo conventions for planning")
  }

  @Test
  fun `every excluded root is pruned from discovery output`() {
    val repo = Files.createTempDirectory("goal-context-all-roots")
    GoalPlanningDiscoveryExclusions.excludedRoots.forEach { root ->
      val agent = Files.createDirectories(repo.resolve(root).resolve("nested/agent"))
      Files.writeString(agent.resolve("history.md"), "excluded history")
    }
    val moduleAgent = Files.createDirectories(repo.resolve("tooling/agent"))
    Files.writeString(moduleAgent.resolve("history.md"), "tooling history")

    val context = FileSystemGoalPlanningContextDiscovery().discover(repo)

    assertEquals(listOf("tooling/agent/history.md"), context.boundaryMemory.keys.toList())
    assertFalse(
      GoalPlanningDiscoveryExclusions.excludedRoots.any { root ->
        context.boundaryMemory.keys.any { path -> path.startsWith(root) }
      },
    )
  }

  @Test
  fun `symlinks into an excluded root stay denied after canonicalization`() {
    val repo = Files.createTempDirectory("goal-context-symlink-exclusion")
    val packAgent = Files.createDirectories(repo.resolve("platform-packs/kmp/agent"))
    val packHistory = Files.writeString(packAgent.resolve("history.md"), "pack history must not be discovered")
    val outside = Files.createTempDirectory("goal-context-outside")
    val outsideAgent = Files.createDirectories(outside.resolve("agent"))
    Files.writeString(outsideAgent.resolve("history.md"), "outside history")
    val safeAgent = Files.createDirectories(repo.resolve("modules/safe/agent"))
    Files.writeString(safeAgent.resolve("history.md"), "safe history")

    val linkable = runCatching {
      Files.createSymbolicLink(repo.resolve("modules/linked-dir"), packAgent.parent)
      Files.createDirectories(repo.resolve("modules/linked-file/agent"))
      Files.createSymbolicLink(repo.resolve("modules/linked-file/agent/history.md"), packHistory)
      Files.createSymbolicLink(repo.resolve("modules/escaped"), outside)
    }.isSuccess
    assumeTrue(linkable, "filesystem cannot create symbolic links")

    val context = FileSystemGoalPlanningContextDiscovery().discover(repo)

    assertEquals(listOf("modules/safe/agent/history.md"), context.boundaryMemory.keys.toList())
    assertFalse(context.boundaryMemory.values.any { value -> "must not be discovered" in value })
    assertFalse(context.boundaryMemory.values.any { value -> "outside" in value })
  }

  @Test
  fun `file count and total byte budgets exhaust deterministically in sorted order`() {
    assertEquals(32, GoalPlanningContext.MAX_DISCOVERY_FILE_COUNT)
    assertEquals(4_096, GoalPlanningContext.MAX_DISCOVERY_EXCERPT_BYTES)
    assertEquals(32 * 1_024L, GoalPlanningContext.MAX_DISCOVERY_TOTAL_BYTES)

    val repo = Files.createTempDirectory("goal-context-bounds")
    Files.writeString(repo.resolve("AGENTS.md"), "repo conventions")
    repeat(40) { index ->
      val agent = Files.createDirectories(repo.resolve("modules/module-%02d/agent".format(index)))
      Files.writeString(agent.resolve("history.md"), "history-$index")
      Files.writeString(agent.resolve("decisions.md"), "decisions-$index")
    }

    val context = FileSystemGoalPlanningContextDiscovery().discover(repo)

    assertEquals(GoalPlanningContext.MAX_DISCOVERY_FILE_COUNT, context.boundaryMemory.size)
    assertEquals(
      (0 until 16).flatMap { index ->
        listOf(
          "modules/module-%02d/agent/history.md".format(index),
          "modules/module-%02d/agent/decisions.md".format(index),
        )
      },
      context.boundaryMemory.keys.toList(),
    )
    assertTrue(context.validationGuidance.isEmpty(), "file-count exhaustion omits the later category entirely")
  }

  @Test
  fun `oversized boundary memory files are excerpt bounded`() {
    val repo = Files.createTempDirectory("goal-context-excerpt")
    val agent = Files.createDirectories(repo.resolve("modules/big/agent"))
    val oversized = "x".repeat(10_000)
    Files.writeString(agent.resolve("history.md"), oversized)
    Files.writeString(repo.resolve("AGENTS.md"), oversized)

    val context = FileSystemGoalPlanningContextDiscovery().discover(repo)
    val excerptBodies = (context.boundaryMemory.values + context.validationGuidance)
      .map { value -> value.substringBefore("\n…[") }

    assertTrue(excerptBodies.all { value -> value.length <= GoalPlanningContext.MAX_DISCOVERY_EXCERPT_BYTES })
    assertTrue(excerptBodies.sumOf(String::length) <= GoalPlanningContext.MAX_DISCOVERY_TOTAL_BYTES.toInt())
    assertContains(context.boundaryMemory.getValue("modules/big/agent/history.md"), "…[")
  }
}

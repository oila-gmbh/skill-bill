package skillbill.goalplanning

import skillbill.ports.goalrunner.model.GoalPlanningContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemGoalPlanningContextDiscoveryTest {
  @Test
  fun `discovery uses boundary memory allowlist and file count bound without platform yaml`() {
    val repo = Files.createTempDirectory("goal-context-allowlist")
    val packs = Files.createDirectories(repo.resolve("platform-packs"))
    Files.writeString(repo.resolve("AGENTS.md"), "repo conventions")
    repeat(40) { index ->
      val pack = Files.createDirectories(packs.resolve("pack-%02d".format(index)))
      Files.writeString(pack.resolve("platform.yaml"), "pack-$index-platform")
      val agent = Files.createDirectories(pack.resolve("agent"))
      Files.writeString(agent.resolve("history.md"), "history-$index")
      Files.writeString(agent.resolve("decisions.md"), "decisions-$index")
      Files.writeString(pack.resolve("ignored.txt"), "must not be discovered")
      Files.writeString(
        Files.createDirectories(pack.resolve("nested")).resolve("platform.yaml"),
        "nested must not be discovered",
      )
    }
    val context = FileSystemGoalPlanningContextDiscovery().discover(repo)

    assertEquals(GoalPlanningContext.MAX_DISCOVERY_FILE_COUNT, context.boundaryMemory.size)
    assertTrue(
      context.boundaryMemory.keys.all { path ->
        path.matches(Regex("platform-packs/pack-\\d+/agent/history\\.md"))
      },
      "file-count exhaustion omits later categories; surviving keys are history in sorted pack order",
    )
    assertTrue(context.validationGuidance.isEmpty())
    assertFalse(context.boundaryMemory.values.any { value -> "nested must not be discovered" in value })
    assertFalse(context.boundaryMemory.keys.any { path -> path.contains("platform.yaml") })
  }

  @Test
  fun `discovery bounds excerpts and total bytes without reading platform yaml`() {
    val repo = Files.createTempDirectory("goal-context-bounds")
    val pack = Files.createDirectories(repo.resolve("platform-packs/kotlin"))
    val oversized = "x".repeat(10_000)
    Files.writeString(pack.resolve("platform.yaml"), oversized)
    val agent = Files.createDirectories(pack.resolve("agent"))
    Files.writeString(agent.resolve("history.md"), oversized)
    Files.writeString(agent.resolve("decisions.md"), oversized)
    Files.writeString(repo.resolve("AGENTS.md"), oversized)

    val context = FileSystemGoalPlanningContextDiscovery().discover(repo)
    val allExcerpts = context.boundaryMemory.values + context.validationGuidance
    val excerptBodies = allExcerpts.map { value -> value.substringBefore("\n…[") }

    assertFalse(context.boundaryMemory.keys.any { path -> path.contains("platform.yaml") })
    assertTrue(excerptBodies.all { value -> value.length <= GoalPlanningContext.MAX_DISCOVERY_EXCERPT_BYTES })
    assertTrue(excerptBodies.sumOf(String::length) <= GoalPlanningContext.MAX_DISCOVERY_TOTAL_BYTES.toInt())
    assertContains(context.boundaryMemory.keys, "platform-packs/kotlin/agent/history.md")
    assertContains(context.boundaryMemory.getValue("platform-packs/kotlin/agent/history.md"), "…[")
  }

  @Test
  fun `discovery excludes pack directories and files whose real paths leave the repository`() {
    val repo = Files.createTempDirectory("goal-context-symlink-containment")
    val packs = Files.createDirectories(repo.resolve("platform-packs"))
    val outside = Files.createTempDirectory("goal-context-outside")
    Files.writeString(outside.resolve("platform.yaml"), "outside-pack")
    val outsideAgent = Files.createDirectories(outside.resolve("agent"))
    val outsideHistory = Files.writeString(outsideAgent.resolve("history.md"), "outside-history")

    val safe = Files.createDirectories(packs.resolve("safe"))
    val safeAgent = Files.createDirectories(safe.resolve("agent"))
    Files.writeString(safeAgent.resolve("history.md"), "safe-history")
    Files.writeString(safe.resolve("platform.yaml"), "safe-pack")
    Files.createSymbolicLink(packs.resolve("escaped"), outside)

    val linkedFilePack = Files.createDirectories(packs.resolve("linked-file"))
    val linkedAgent = Files.createDirectories(linkedFilePack.resolve("agent"))
    Files.createSymbolicLink(linkedAgent.resolve("history.md"), outsideHistory)

    val context = FileSystemGoalPlanningContextDiscovery().discover(repo)

    assertEquals(listOf("platform-packs/safe/agent/history.md"), context.boundaryMemory.keys.toList())
    assertTrue(context.boundaryMemory.values.all { value -> "outside" !in value })
  }

  @Test
  fun `nine oversized platform yaml packs leave boundary memory and AGENTS guidance`() {
    assertEquals(32, GoalPlanningContext.MAX_DISCOVERY_FILE_COUNT)
    assertEquals(4_096, GoalPlanningContext.MAX_DISCOVERY_EXCERPT_BYTES)
    assertEquals(32 * 1_024L, GoalPlanningContext.MAX_DISCOVERY_TOTAL_BYTES)

    val repo = Files.createTempDirectory("goal-context-nine-pack")
    val packs = Files.createDirectories(repo.resolve("platform-packs"))
    val oversizedPlatform = "y".repeat(GoalPlanningContext.MAX_DISCOVERY_EXCERPT_BYTES + 1)
    Files.writeString(repo.resolve("AGENTS.md"), "repo conventions for planning")
    repeat(9) { index ->
      val pack = Files.createDirectories(packs.resolve("pack-$index"))
      Files.writeString(pack.resolve("platform.yaml"), oversizedPlatform)
      val agent = Files.createDirectories(pack.resolve("agent"))
      Files.writeString(agent.resolve("history.md"), "history-$index")
      Files.writeString(agent.resolve("decisions.md"), "decision-$index")
    }

    val context = FileSystemGoalPlanningContextDiscovery().discover(repo)

    assertTrue(context.boundaryMemory.isNotEmpty())
    assertTrue(context.validationGuidance.isNotEmpty())
    assertContains(context.validationGuidance, "repo conventions for planning")
    assertTrue(
      context.boundaryMemory.keys.any { path -> path.endsWith("/agent/history.md") },
    )
    assertTrue(
      context.boundaryMemory.keys.any { path -> path.endsWith("/agent/decisions.md") },
    )
  }
}

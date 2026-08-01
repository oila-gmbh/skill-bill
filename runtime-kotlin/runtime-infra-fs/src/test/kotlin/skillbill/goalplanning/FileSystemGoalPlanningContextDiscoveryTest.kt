package skillbill.goalplanning

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemGoalPlanningContextDiscoveryTest {
  @Test
  fun `discovery uses an explicit allowlist and file count bound`() {
    val repo = Files.createTempDirectory("goal-context-allowlist")
    val packs = Files.createDirectories(repo.resolve("platform-packs"))
    repeat(40) { index ->
      val pack = Files.createDirectories(packs.resolve("pack-$index"))
      Files.writeString(pack.resolve("platform.yaml"), "pack-$index")
      Files.writeString(pack.resolve("ignored.txt"), "must not be discovered")
      Files.writeString(
        Files.createDirectories(pack.resolve("nested")).resolve("platform.yaml"),
        "nested must not be discovered",
      )
    }
    val context = FileSystemGoalPlanningContextDiscovery().discover(repo)

    assertEquals(32, context.platformPacks.size)
    assertTrue(
      context.platformPacks.keys.all { path ->
        path.matches(Regex("platform-packs/pack-\\d+/platform\\.yaml"))
      },
    )
    assertFalse(context.platformPacks.values.any { value -> "nested must not be discovered" in value })
    assertTrue(context.validationGuidance.isEmpty())
  }

  @Test
  fun `discovery bounds excerpts and total bytes across targeted files`() {
    val repo = Files.createTempDirectory("goal-context-bounds")
    val pack = Files.createDirectories(repo.resolve("platform-packs/kotlin"))
    val oversized = "x".repeat(10_000)
    Files.writeString(pack.resolve("platform.yaml"), oversized)
    val agent = Files.createDirectories(pack.resolve("agent"))
    Files.writeString(agent.resolve("history.md"), oversized)
    Files.writeString(pack.resolve("agent/decisions.md"), oversized)
    Files.writeString(repo.resolve("AGENTS.md"), oversized)

    val context = FileSystemGoalPlanningContextDiscovery().discover(repo)
    val allExcerpts = context.platformPacks.values + context.boundaryMemory.values + context.validationGuidance
    val excerptBodies = allExcerpts.map { value -> value.substringBefore("\n…[") }

    assertTrue(excerptBodies.all { value -> value.length <= 4_096 })
    assertTrue(excerptBodies.sumOf(String::length) <= 32 * 1_024)
    assertContains(context.platformPacks.getValue("platform-packs/kotlin/platform.yaml"), "…[")
    assertContains(context.boundaryMemory.keys, "platform-packs/kotlin/agent/history.md")
  }
}

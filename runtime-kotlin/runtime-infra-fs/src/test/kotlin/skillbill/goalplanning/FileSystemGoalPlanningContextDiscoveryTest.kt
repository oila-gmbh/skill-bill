package skillbill.goalplanning

import java.nio.file.Files
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

  @Test
  fun `discovery excludes pack directories and files whose real paths leave the repository`() {
    val repo = Files.createTempDirectory("goal-context-symlink-containment")
    val packs = Files.createDirectories(repo.resolve("platform-packs"))
    val outside = Files.createTempDirectory("goal-context-outside")
    val outsidePlatform = Files.writeString(outside.resolve("platform.yaml"), "outside-pack")
    val outsideAgent = Files.createDirectories(outside.resolve("agent"))
    Files.writeString(outsideAgent.resolve("history.md"), "outside-history")

    val safe = Files.createDirectories(packs.resolve("safe"))
    Files.writeString(safe.resolve("platform.yaml"), "safe-pack")
    Files.createSymbolicLink(packs.resolve("escaped"), outside)

    val linkedFilePack = Files.createDirectories(packs.resolve("linked-file"))
    Files.createSymbolicLink(linkedFilePack.resolve("platform.yaml"), outsidePlatform)

    val context = FileSystemGoalPlanningContextDiscovery().discover(repo)

    assertEquals(listOf("platform-packs/safe/platform.yaml"), context.platformPacks.keys.toList())
    assertTrue(context.platformPacks.values.all { value -> "outside" !in value })
    assertTrue(context.boundaryMemory.isEmpty())
  }
}

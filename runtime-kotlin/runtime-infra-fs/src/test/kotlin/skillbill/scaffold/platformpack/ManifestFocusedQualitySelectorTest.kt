package skillbill.scaffold.platformpack

import org.junit.jupiter.api.Test
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityCategory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestFocusedQualitySelectorTest {
  @Test
  fun `owned Kotlin paths select the manifest checker and every focused category deterministically`() {
    val workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
    val repoRoot = generateSequence(workingDirectory) { it.parent }
      .first { Files.isDirectory(it.resolve("platform-packs")) }
    val selector = ManifestFocusedQualitySelector(repoRoot.resolve("platform-packs"))
    val paths = listOf(
      "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/Example.kt",
      "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/Runner.kt",
    )
    val packs = discoverPlatformPacks(repoRoot.resolve("platform-packs"))
    assertTrue(
      packs.any { it.slug == "kotlin" && it.routingSignals.path.contains(".kt") },
      "Kotlin pack was not loaded from $repoRoot: ${packs.map { it.slug to it.routingSignals.path }}",
    )

    val first = selector.select(paths.reversed())
    val second = selector.select(paths)

    assertEquals(first, second)
    assertEquals(FeatureTaskRuntimeFocusedQualityCategory.entries.toSet(), first.checks.map { it.category }.toSet())
    assertTrue(first.checks.all { it.checkerSkill.endsWith("content.md") })
    assertTrue(first.checks.all { it.ownedPaths == paths.sorted() })
  }
}

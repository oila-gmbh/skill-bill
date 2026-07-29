package skillbill.scaffold.platformpack

import org.junit.jupiter.api.Test
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityCategory
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestFocusedQualitySelectorTest {
  @Test
  fun `owned Kotlin paths select the manifest checker and every focused category deterministically`() {
    val repoRoot = Path.of(System.getProperty("user.dir")).parent
    val selector = ManifestFocusedQualitySelector(repoRoot.resolve("platform-packs"))
    val paths = listOf(
      "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/Example.kt",
      "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/Runner.kt",
    )

    val first = selector.select(paths.reversed())
    val second = selector.select(paths)

    assertEquals(first, second)
    assertEquals(FeatureTaskRuntimeFocusedQualityCategory.entries.toSet(), first.checks.map { it.category }.toSet())
    assertTrue(first.checks.all { it.checkerSkill.endsWith("content.md") })
    assertTrue(first.checks.all { it.ownedPaths == paths.sorted() })
  }
}

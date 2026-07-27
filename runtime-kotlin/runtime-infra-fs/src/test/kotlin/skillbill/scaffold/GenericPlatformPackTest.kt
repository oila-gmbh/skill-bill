package skillbill.scaffold

import org.junit.jupiter.api.Test
import skillbill.nativeagent.composition.parseNativeAgentBundle
import skillbill.scaffold.platformpack.loadPlatformPack
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GenericPlatformPackTest {
  @Test
  fun `generic pack is review capable fallback without concrete scoring signals`() {
    val packRoot = repoRootFromTest().resolve("platform-packs/generic")
    val pack = loadPlatformPack(packRoot)

    assertEquals(setOf("code-review"), pack.fallbackCapabilities)
    assertEquals(APPROVED_CODE_REVIEW_AREAS, pack.declaredFiles.areas.keys)
    assertEquals(APPROVED_CODE_REVIEW_AREAS.sorted(), pack.declaredCodeReviewAreas.sorted())
    assertTrue(pack.routingSignals.path.isEmpty())
    assertTrue(pack.routingSignals.content.isEmpty())
    assertFalse(pack.routingSignals.strong.any { it.startsWith(".") || "*" in it || "/" in it })
  }

  @Test
  fun `generic governed sources and provider neutral agents are complete`() {
    val packRoot = repoRootFromTest().resolve("platform-packs/generic")
    val contentFiles = Files.walk(packRoot).use { stream ->
      stream.filter { it.fileName.toString() == "content.md" }.toList()
    }
    assertEquals(11, contentFiles.size)
    contentFiles.forEach { path ->
      val content = Files.readString(path)
      assertTrue(content.length > 300, "$path must contain substantive governed guidance")
      assertFalse("SKILL.md" in path.toString())
    }

    val agents = parseNativeAgentBundle(
      packRoot.resolve("code-review/bill-generic-code-review/native-agents/agents.yaml"),
    )
    assertEquals(10, agents.size)
    assertTrue(agents.all { it.composition?.kind?.wireValue == "governed-content" })
  }
}

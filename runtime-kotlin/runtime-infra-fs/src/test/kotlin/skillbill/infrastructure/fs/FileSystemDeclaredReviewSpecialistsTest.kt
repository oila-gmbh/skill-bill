package skillbill.infrastructure.fs

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileSystemDeclaredReviewSpecialistsTest {
  @Test
  fun `no platform-packs directory yields no specialists`() {
    val repoRoot = Files.createTempDirectory("declared-specialists-empty")
    val specialists = FileSystemDeclaredReviewSpecialists().declaredSpecialists(repoRoot)
    assertEquals(emptyList(), specialists)
  }

  @Test
  fun `a pack directory without a manifest contributes no specialists`() {
    val repoRoot = Files.createTempDirectory("declared-specialists-absent")
    val packsRoot = Files.createDirectories(repoRoot.resolve("platform-packs"))
    Files.createDirectory(packsRoot.resolve("kotlin"))
    val specialists = FileSystemDeclaredReviewSpecialists().declaredSpecialists(repoRoot)
    assertEquals(emptyList(), specialists)
  }

  @Test
  fun `a malformed manifest loud-fails instead of being silently swallowed`() {
    val repoRoot = Files.createTempDirectory("declared-specialists-malformed")
    val packsRoot = Files.createDirectories(repoRoot.resolve("platform-packs"))
    val packDir = Files.createDirectory(packsRoot.resolve("broken"))
    Files.writeString(packDir.resolve("platform.yaml"), "areas: [unclosed")
    var threw = false
    try {
      FileSystemDeclaredReviewSpecialists().declaredSpecialists(repoRoot)
    } catch (_: Exception) {
      threw = true
    }
    assertTrue(threw, "a malformed manifest must loud-fail, not silently contribute zero specialists")
  }
}

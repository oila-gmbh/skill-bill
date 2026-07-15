package skillbill.managedskill

import kotlin.io.path.createDirectory
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import java.nio.file.Files

class OpaqueSkillBundleScannerTest {
  private val scanner = OpaqueSkillBundleScanner()

  @Test
  fun `scans root skill and supporting files deterministically`() {
    val root = Files.createTempDirectory("opaque-skill")
    root.resolve("SKILL.md").writeText("---\nname: sample-skill\ndescription: Sample\n---\nBody")
    root.resolve("notes.txt").writeText("support")
    val first = scanner.scan(root, setOf("bill-code-review"))
    val second = scanner.scan(root, setOf("bill-code-review"))
    assertEquals(listOf("SKILL.md", "notes.txt"), first.files.map { root.relativize(it).toString() })
    assertEquals(first.contentHash, second.contentHash)
    root.resolve("notes.txt").writeText("changed")
    assertNotEquals(first.contentHash, scanner.scan(root, emptySet()).contentHash)
  }

  @Test
  fun `rejects symlinks before returning a bundle`() {
    val root = Files.createTempDirectory("opaque-skill")
    root.resolve("SKILL.md").writeText("---\nname: sample-skill\ndescription: Sample\n---")
    val target = root.resolve("target").createDirectory()
    root.resolve("linked").createSymbolicLinkPointingTo(target)
    assertFailsWith<InvalidOpaqueSkillBundleException> { scanner.scan(root, emptySet()) }
  }

  @Test
  fun `rejects protected names case insensitively`() {
    val root = Files.createTempDirectory("opaque-skill")
    root.resolve("SKILL.md").writeText("---\nname: protected\ndescription: Sample\n---")
    assertFailsWith<IllegalArgumentException> { scanner.scan(root, setOf("PROTECTED")) }
  }
}

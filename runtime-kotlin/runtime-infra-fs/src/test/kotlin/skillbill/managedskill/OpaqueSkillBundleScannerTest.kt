package skillbill.managedskill

import kotlin.io.path.createDirectory
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import java.nio.file.FileSystems
import java.nio.file.Files
import java.net.URI

class OpaqueSkillBundleScannerTest {
  private val scanner = OpaqueSkillBundleScanner()

  @Test
  fun `scans root skill and supporting files deterministically`() {
    val root = Files.createTempDirectory("opaque-skill")
    root.resolve("SKILL.md").writeText("---\nname: sample-skill\ndescription: Sample\n---\nBody")
    root.resolve("notes.txt").writeText("support")
    val first = scanner.scan(root, setOf("bill-code-review"))
    val second = scanner.scan(root, setOf("bill-code-review"))
    assertEquals(listOf("SKILL.md", "notes.txt"), first.files.map { it.relativePath })
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

  @Test
  fun `rejects duplicate and nested frontmatter ownership keys`() {
    val root = Files.createTempDirectory("opaque-skill")
    root.resolve("SKILL.md").writeText("---\nname: protected\nname: sample-skill\ndescription: Sample\n---")
    assertFailsWith<InvalidOpaqueSkillBundleException> { scanner.scan(root, setOf("protected")) }
    root.resolve("SKILL.md").writeText("---\nmetadata:\n  name: sample-skill\ndescription: Sample\n---")
    assertFailsWith<InvalidOpaqueSkillBundleException> { scanner.scan(root, emptySet()) }
    root.resolve("SKILL.md").writeText("---\nname: sample-skill\ndescription: Sample\nmetadata:\n  name: nested\n---")
    assertFailsWith<InvalidOpaqueSkillBundleException> { scanner.scan(root, emptySet()) }
  }

  @Test
  fun `rejects aliases merges and custom tags`() {
    val root = Files.createTempDirectory("opaque-skill")
    root.resolve("SKILL.md").writeText("---\nname: &owned sample-skill\ndescription: Sample\ncopy: *owned\n---")
    assertFailsWith<InvalidOpaqueSkillBundleException> { scanner.scan(root, emptySet()) }
    root.resolve("SKILL.md").writeText("---\nname: sample-skill\ndescription: Sample\nmetadata: !custom value\n---")
    assertFailsWith<InvalidOpaqueSkillBundleException> { scanner.scan(root, emptySet()) }
  }

  @Test
  fun `accepts quoted exclamation marks but rejects numeric tags`() {
    val root = Files.createTempDirectory("opaque-skill")
    root.resolve("SKILL.md").writeText("---\nname: sample-skill\ndescription: \"Works!\"\n---")
    assertEquals("Works!", scanner.scan(root, emptySet()).description)
    root.resolve("SKILL.md").writeText("---\nname: sample-skill\ndescription: Sample\nmetadata: !42 value\n---")
    assertFailsWith<InvalidOpaqueSkillBundleException> { scanner.scan(root, emptySet()) }
  }

  @Test
  fun `translates malformed event parser input to the bundle exception`() {
    val root = Files.createTempDirectory("opaque-skill")
    root.resolve("SKILL.md").writeText("---\nname: sample-skill\ndescription: Sample\nmetadata: [unterminated\n---")
    assertFailsWith<InvalidOpaqueSkillBundleException> { scanner.scan(root, emptySet()) }
  }

  @Test
  fun `returns captured bytes rather than mutable live paths`() {
    val root = Files.createTempDirectory("opaque-skill")
    val support = root.resolve("notes.txt")
    root.resolve("SKILL.md").writeText("---\nname: sample-skill\ndescription: Sample\n---")
    support.writeText("before")
    val bundle = scanner.scan(root, emptySet())
    support.writeText("after")
    assertEquals("before", bundle.files.single { it.relativePath == "notes.txt" }.content.toString(Charsets.UTF_8))
    val returned = bundle.files.single { it.relativePath == "notes.txt" }.content
    returned[0] = 'X'.code.toByte()
    assertEquals("before", bundle.files.single { it.relativePath == "notes.txt" }.content.toString(Charsets.UTF_8))
  }

  @Test
  fun `scans valid bundles on providers without secure directory streams`() {
    val archive = Files.createTempFile("opaque-skill", ".zip")
    Files.delete(archive)
    FileSystems.newFileSystem(URI.create("jar:${archive.toUri()}"), mapOf("create" to "true")).use { fileSystem ->
      val root = fileSystem.getPath("/")
      root.resolve("SKILL.md").writeText("---\nname: sample-skill\ndescription: Sample\n---")
      root.resolve("notes.txt").writeText("support")

      val bundle = scanner.scan(root, emptySet())
      assertEquals("sample-skill", bundle.name)
      assertEquals(listOf("SKILL.md", "notes.txt"), bundle.files.map { it.relativePath })
    }
  }

  @Test
  fun `scans ordinary file provider directory when secure traversal is unavailable`() {
    val root = Files.createTempDirectory("opaque-skill-fallback")
    root.resolve("SKILL.md").writeText("---\nname: sample-skill\ndescription: Sample\n---")
    root.resolve("notes.txt").writeText("support")

    val bundle = OpaqueSkillBundleScanner(useSecureDirectoryStreams = false).scan(root, emptySet())
    assertEquals(listOf("SKILL.md", "notes.txt"), bundle.files.map { it.relativePath })
  }

  @Test
  fun `scans ordinary file provider skill file when secure traversal is unavailable`() {
    val root = Files.createTempDirectory("opaque-skill-file-fallback")
    val skill = root.resolve("SKILL.md")
    skill.writeText("---\nname: sample-skill\ndescription: Sample\n---")
    root.resolve("notes.txt").writeText("not selected")

    val bundle = OpaqueSkillBundleScanner(useSecureDirectoryStreams = false).scan(skill, emptySet())
    assertEquals(listOf("SKILL.md"), bundle.files.map { it.relativePath })
  }

  @Test
  fun `rejects a root replaced before its identity bound handle is opened`() {
    val parent = Files.createTempDirectory("opaque-root-swap")
    val root = parent.resolve("selected").createDirectory()
    root.resolve("SKILL.md").writeText("---\nname: original\ndescription: Original\n---")
    val scanner = OpaqueSkillBundleScanner { selected ->
      Files.move(selected, parent.resolve("displaced"))
      selected.createDirectory()
      selected.resolve("SKILL.md").writeText("---\nname: replacement\ndescription: Replacement\n---")
    }

    assertFailsWith<InvalidOpaqueSkillBundleException> { scanner.scan(root, emptySet()) }
  }
}

package skillbill.scaffold

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AuthoringRenderOutputTest {
  private val tempRoot: Path = Files.createTempDirectory("skillbill-authoring-render-")

  @AfterTest
  fun cleanup() {
    if (Files.exists(tempRoot)) {
      Files.walk(tempRoot).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
      }
    }
  }

  @Test
  fun `renderAuthoringTarget emits deterministic wrapper stdout from renderWrapper without writing`() {
    val repoRoot = tempRoot.resolve("horizontal-repo")
    val skillDir = repoRoot.resolve("skills/bill-render-fixture")
    Files.createDirectories(skillDir)
    Files.writeString(skillDir.resolve("SKILL.md"), "stale wrapper\n")
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: bill-render-fixture
      description: Fixture skill for render output.
      ---

      # Authored Body
      """.trimIndent() + "\n",
    )
    val target = resolveTarget(repoRoot, "bill-render-fixture")
    val skillBytesBefore = Files.readAllBytes(skillDir.resolve("SKILL.md"))
    val expectedStdout =
      "===== SKILL.md: skills/bill-render-fixture/SKILL.md =====\n" +
        renderWrapper(target).trimEnd('\r', '\n') +
        "\n"

    val first = renderAuthoringTarget(repoRoot, "bill-render-fixture")
    val second = renderAuthoringTarget(repoRoot, "bill-render-fixture")

    assertEquals(expectedStdout, first.stdout)
    assertEquals(first.stdout, second.stdout)
    assertFalse('\r' in first.stdout, "render stdout must use LF line endings only")
    assertEquals("stale wrapper\n", Files.readString(skillDir.resolve("SKILL.md")))
    assertEquals(skillBytesBefore.toList(), Files.readAllBytes(skillDir.resolve("SKILL.md")).toList())
  }

  @Test
  fun `platform-pack render includes pointer blocks from renderPointer in manifest declaration order`() {
    val fixture = writePlatformRenderFixture()

    val rendered = renderAuthoringTarget(fixture.repoRoot, "bill-fixturepack-code-review")
    val expectedZ = renderPointer(
      fixture.repoRoot,
      fixture.packRoot,
      loadPlatformManifest(fixture.packRoot).pointers[0],
    )
    val expectedA = renderPointer(
      fixture.repoRoot,
      fixture.packRoot,
      loadPlatformManifest(fixture.packRoot).pointers[1],
    )

    assertEquals(
      listOf(
        "===== SKILL.md: platform-packs/fixturepack/code-review/bill-fixturepack-code-review/SKILL.md =====",
        "===== pointer: platform-packs/fixturepack/code-review/bill-fixturepack-code-review/z.md =====",
        "===== pointer: platform-packs/fixturepack/code-review/bill-fixturepack-code-review/a.md =====",
      ),
      rendered.blocks.map { block -> block.header },
    )
    assertEquals(expectedZ, rendered.blocks[1].content)
    assertEquals(expectedA, rendered.blocks[2].content)
    assertFalse(Files.exists(fixture.zPointer), "render must not create pointer files")
    assertFalse(Files.exists(fixture.aPointer), "render must not create pointer files")
    assertEquals("stale platform wrapper\n", Files.readString(fixture.skillDir.resolve("SKILL.md")))
  }

  private fun writePlatformRenderFixture(): PlatformRenderFixture {
    val repoRoot = tempRoot.resolve("platform-repo")
    val packRoot = repoRoot.resolve("platform-packs/fixturepack")
    val skillDir = packRoot.resolve("code-review/bill-fixturepack-code-review")
    Files.createDirectories(skillDir)
    Files.createDirectories(repoRoot.resolve("shared"))
    Files.writeString(repoRoot.resolve("shared/z.md"), "# z")
    Files.writeString(repoRoot.resolve("shared/a.md"), "# a")
    Files.writeString(skillDir.resolve("SKILL.md"), "stale platform wrapper\n")
    Files.writeString(skillDir.resolve("content.md"), platformFixtureContent())
    Files.writeString(packRoot.resolve("platform.yaml"), platformFixtureManifest())
    return PlatformRenderFixture(
      repoRoot = repoRoot,
      packRoot = packRoot,
      skillDir = skillDir,
      zPointer = skillDir.resolve("z.md"),
      aPointer = skillDir.resolve("a.md"),
    )
  }

  private fun platformFixtureContent(): String = """
    ---
    name: bill-fixturepack-code-review
    description: Fixture platform render skill.
    ---

    # Platform Body
  """.trimIndent() + "\n"

  private fun platformFixtureManifest(): String = """
    platform: fixturepack
    contract_version: "1.1"

    routing_signals:
      strong:
        - ".fixture"

    declared_code_review_areas: []

    declared_files:
      baseline: code-review/bill-fixturepack-code-review/SKILL.md

    area_metadata: {}

    pointers:
      code-review/bill-fixturepack-code-review:
        - name: z.md
          target: shared/z.md
        - name: a.md
          target: shared/a.md
  """.trimIndent() + "\n"
}

private data class PlatformRenderFixture(
  val repoRoot: Path,
  val packRoot: Path,
  val skillDir: Path,
  val zPointer: Path,
  val aPointer: Path,
)

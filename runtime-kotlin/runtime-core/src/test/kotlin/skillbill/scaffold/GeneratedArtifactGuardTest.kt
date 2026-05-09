package skillbill.scaffold

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeneratedArtifactGuardTest {
  private val tempRoot: Path = Files.createTempDirectory("skillbill-generated-guard-")

  @AfterTest
  fun cleanup() {
    if (Files.exists(tempRoot)) {
      Files.walk(tempRoot).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
      }
    }
  }

  @Test
  fun `guard remains dormant when source tree has no generated outputs`() {
    val repoRoot = tempRoot.resolve("baseline-repo")
    val skillDir = repoRoot.resolve("skills/bill-code-review")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: bill-code-review
      description: Authored source without generated wrapper.
      ---

      # Fixture Body
      """.trimIndent() + "\n",
    )

    val report = validateGeneratedArtifactGuard(repoRoot)

    assertTrue(report.passed, report.issues.joinToString("\n"))
  }

  @Test
  fun `guard rejects new governed SKILL_md outputs`() {
    val repoRoot = tempRoot.resolve("new-skill-repo")
    writeGovernedSkillOutput(repoRoot, "skills/bill-new-skill")

    val report = validateGeneratedArtifactGuard(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("skills/bill-new-skill/SKILL.md") &&
          it.contains("committed governed SKILL.md output is not allowed")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `guard rejects new platform pack governed SKILL_md outputs`() {
    val repoRoot = tempRoot.resolve("new-platform-pack-skill-repo")
    writeGovernedSkillOutput(
      repoRoot,
      "platform-packs/fixturepack/code-review/bill-fixturepack-code-review",
    )

    val report = validateGeneratedArtifactGuard(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("platform-packs/fixturepack/code-review/bill-fixturepack-code-review/SKILL.md") &&
          it.contains("committed governed SKILL.md output is not allowed")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `guard rejects new platform_yaml pointer files`() {
    val repoRoot = tempRoot.resolve("new-pointer-repo")
    writePointerFixture(
      repoRoot = repoRoot,
      pack = "fixturepack",
      skillRelativeDir = "code-review/bill-fixturepack-code-review",
      pointerName = "shell-ceremony.md",
    )

    val report = validateGeneratedArtifactGuard(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("platform-packs/fixturepack/code-review/bill-fixturepack-code-review/shell-ceremony.md") &&
          it.contains("committed platform.yaml pointer file is not allowed")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `guard ignores untracked local generated outputs when git index is available`() {
    val repoRoot = tempRoot.resolve("git-index-repo")
    Files.createDirectories(repoRoot)
    runGit(repoRoot, "init")
    writeGovernedSkillOutput(repoRoot, "skills/bill-tracked-generated")
    writeGovernedSkillOutput(repoRoot, "skills/bill-untracked-generated")
    runGit(repoRoot, "add", "skills/bill-tracked-generated")

    val report = validateGeneratedArtifactGuard(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("skills/bill-tracked-generated/SKILL.md") &&
          it.contains("committed governed SKILL.md output is not allowed")
      },
      report.issues.joinToString("\n"),
    )
    assertFalse(
      report.issues.any { it.contains("skills/bill-untracked-generated/SKILL.md") },
      report.issues.joinToString("\n"),
    )
  }

  private fun writeGovernedSkillOutput(repoRoot: Path, relativeDir: String) {
    val skillDir = repoRoot.resolve(relativeDir)
    val skillName = skillDir.fileName.toString()
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: $skillName
      description: Fixture skill.
      ---

      # Fixture Body
      """.trimIndent() + "\n",
    )
    Files.writeString(skillDir.resolve("SKILL.md"), "generated wrapper\n")
  }

  private fun writePointerFixture(repoRoot: Path, pack: String, skillRelativeDir: String, pointerName: String) {
    val packRoot = repoRoot.resolve("platform-packs/$pack")
    val pointerFile = packRoot.resolve(skillRelativeDir).resolve(pointerName)
    Files.createDirectories(pointerFile.parent)
    Files.writeString(pointerFile, "../../../../shared/shell.md")
    Files.writeString(
      packRoot.resolve("platform.yaml"),
      """
      platform: $pack
      contract_version: "1.1"

      routing_signals:
        strong:
          - ".fixture"

      declared_code_review_areas: []

      declared_files:
        baseline: code-review/bill-$pack-code-review/content.md

      area_metadata: {}

      pointers:
        $skillRelativeDir:
          - name: $pointerName
            target: shared/shell.md
      """.trimIndent() + "\n",
    )
  }

  private fun runGit(repoRoot: Path, vararg args: String) {
    val process = ProcessBuilder(listOf("git", "-C", repoRoot.toString()) + args)
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().use { reader -> reader.readText() }
    assertEquals(0, process.waitFor(), output)
  }
}

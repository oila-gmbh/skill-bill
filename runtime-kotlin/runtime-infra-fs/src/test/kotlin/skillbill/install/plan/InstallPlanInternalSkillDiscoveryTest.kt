package skillbill.install.plan

import skillbill.error.InvalidInternalSkillClassificationError
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SKILL-102 subtask 1 (PD1): install-plan discovery carries the internal-for classification and
 * enforces the same loud-fail rules as authoring discovery. Fixtures are created inside the tests.
 */
class InstallPlanInternalSkillDiscoveryTest {
  private val tempDirs = mutableListOf<Path>()

  @AfterTest
  fun cleanup() {
    tempDirs.reversed().forEach { dir ->
      if (Files.exists(dir)) {
        Files.walk(dir).use { stream ->
          stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
      }
    }
  }

  @Test
  fun `discoverBaseSkills carries internal-for classification onto the install plan`() {
    val repoRoot = Files.createTempDirectory("skillbill-plan-discovery").also(tempDirs::add)
    seedSkill(repoRoot, "bill-feature", null)
    seedSkill(repoRoot, "bill-feature-task", "bill-feature")

    val skills = discoverBaseSkills(repoRoot.resolve("skills"))

    val child = skills.single { it.name == "bill-feature-task" }
    assertEquals("bill-feature", child.internalFor)
    val parent = skills.single { it.name == "bill-feature" }
    assertEquals(null, parent.internalFor)
  }

  @Test
  fun `validateInstallPlanInternalSkills passes for a valid parent and child`() {
    val skills = listOf(
      planSkill("bill-feature", internalFor = null),
      planSkill("bill-feature-task", internalFor = "bill-feature"),
    )
    validateInstallPlanInternalSkills(skills)
  }

  @Test
  fun `validateInstallPlanInternalSkills fails for an unknown parent`() {
    val skills = listOf(planSkill("bill-feature-task", internalFor = "bill-featur"))
    val error = assertFailsWith<InvalidInternalSkillClassificationError> {
      validateInstallPlanInternalSkills(skills)
    }
    assertTrue(error.message.orEmpty().contains("not a discovered skill"))
    assertTrue(error.message.orEmpty().contains("bill-feature-task"))
  }

  @Test
  fun `validateInstallPlanInternalSkills fails for a self parent`() {
    val skills = listOf(planSkill("bill-feature-task", internalFor = "bill-feature-task"))
    val error = assertFailsWith<InvalidInternalSkillClassificationError> {
      validateInstallPlanInternalSkills(skills)
    }
    assertTrue(error.message.orEmpty().contains("skill itself"))
  }

  @Test
  fun `validateInstallPlanInternalSkills fails for an empty internal-for value`() {
    val skills = listOf(planSkill("bill-feature-task", internalFor = "  "))
    val error = assertFailsWith<InvalidInternalSkillClassificationError> {
      validateInstallPlanInternalSkills(skills)
    }
    assertTrue(error.message.orEmpty().contains("empty value"))
  }

  @Test
  fun `validateInstallPlanInternalSkills fails when parent is itself internal`() {
    val skills = listOf(
      planSkill("bill-other", internalFor = null),
      planSkill("bill-feature", internalFor = "bill-other"),
      planSkill("bill-feature-task", internalFor = "bill-feature"),
    )
    val error = assertFailsWith<InvalidInternalSkillClassificationError> {
      validateInstallPlanInternalSkills(skills)
    }
    assertTrue(error.message.orEmpty().contains("chained internal-for"))
    assertTrue(error.message.orEmpty().contains("bill-feature-task"))
  }

  @Test
  fun `validateInstallPlanInternalSkills fails when a platform-pack skill declares internal-for`() {
    val skills = listOf(
      planSkill("bill-feature", internalFor = null),
      planSkill("bill-kotlin-code-review", internalFor = "bill-feature", kind = InstallPlanSkillKind.PLATFORM_PACK),
    )
    val error = assertFailsWith<InvalidInternalSkillClassificationError> {
      validateInstallPlanInternalSkills(skills)
    }
    assertTrue(error.message.orEmpty().contains("platform-pack skill"))
    assertTrue(error.message.orEmpty().contains("bill-kotlin-code-review"))
  }

  @Test
  fun `validateInstallPlanInternalSkills fails when the parent is a platform-pack skill`() {
    val skills = listOf(
      planSkill("bill-kotlin-code-review", internalFor = null, kind = InstallPlanSkillKind.PLATFORM_PACK),
      planSkill("bill-feature-task", internalFor = "bill-kotlin-code-review"),
    )
    val error = assertFailsWith<InvalidInternalSkillClassificationError> {
      validateInstallPlanInternalSkills(skills)
    }
    assertTrue(error.message.orEmpty().contains("listed base skill"))
  }

  @Test
  fun `a blank internal-for value in content md loud-fails through discovery and validation`() {
    val repoRoot = Files.createTempDirectory("skillbill-plan-blank").also(tempDirs::add)
    val skillDir = repoRoot.resolve("skills/bill-feature-task")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: bill-feature-task
      description: Internal.
      internal-for:
      ---

      Body.
      """.trimIndent(),
    )

    val skills = discoverBaseSkills(repoRoot.resolve("skills"))
    assertEquals("", skills.single().internalFor, "blank value must be preserved, not treated as listed")

    val error = assertFailsWith<InvalidInternalSkillClassificationError> {
      validateInstallPlanInternalSkills(skills)
    }
    assertTrue(error.message.orEmpty().contains("empty value"))
  }

  private fun planSkill(
    name: String,
    internalFor: String?,
    kind: InstallPlanSkillKind = InstallPlanSkillKind.BASE,
  ): InstallPlanSkill = InstallPlanSkill(
    name = name,
    sourceDir = Path.of("/repo/skills/$name").toAbsolutePath().normalize(),
    kind = kind,
    platformSlug = if (kind == InstallPlanSkillKind.PLATFORM_PACK) "kotlin" else null,
    internalFor = internalFor,
  )

  private fun seedSkill(repoRoot: Path, skillName: String, internalFor: String?) {
    val skillDir = repoRoot.resolve("skills/$skillName")
    Files.createDirectories(skillDir)
    val frontmatter = buildString {
      appendLine("---")
      appendLine("name: $skillName")
      appendLine("description: $skillName skill.")
      if (internalFor != null) {
        appendLine("internal-for: $internalFor")
      }
      appendLine("---")
    }
    Files.writeString(skillDir.resolve("content.md"), frontmatter + "\nAuthored body.\n")
  }
}

package skillbill.scaffold.authoring

import skillbill.error.InvalidInternalSkillClassificationError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SKILL-102 subtask 1 (PD1): internal-skill classification parsing and loud-fail rules.
 *
 * Each rule (unknown parent, internal parent, self parent, missing/empty value) has a typed error
 * (`InvalidInternalSkillClassificationError`) with an actionable message naming the offending skill
 * and the rule violated. Fixtures are created inside the tests, not from repo skills.
 */
class InternalSkillClassificationTest {
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
  fun `parseInternalForFrontmatter returns null when key is absent`() {
    val contentFile = writeTempContent(
      """
      ---
      name: bill-feature
      description: Listed skill.
      ---

      Body.
      """.trimIndent(),
    )
    assertNull(parseInternalForFrontmatter(contentFile))
  }

  @Test
  fun `parseInternalForFrontmatter returns trimmed value when key is present`() {
    val contentFile = writeTempContent(
      """
      ---
      name: bill-feature-task
      description: Internal.
      internal-for: bill-feature
      ---

      Body.
      """.trimIndent(),
    )
    assertEquals("bill-feature", parseInternalForFrontmatter(contentFile))
  }

  @Test
  fun `classification passes when no skill declares internal-for`() {
    val targets = mapOf(
      "bill-feature" to target("bill-feature", internalFor = null),
      "bill-code-review" to target("bill-code-review", internalFor = null),
    )
    validateInternalSkillClassification(targets)
  }

  @Test
  fun `classification passes for a valid internal child`() {
    val targets = mapOf(
      "bill-feature" to target("bill-feature", internalFor = null),
      "bill-feature-task" to target("bill-feature-task", internalFor = "bill-feature"),
    )
    validateInternalSkillClassification(targets)
  }

  @Test
  fun `classification fails when internal-for value is blank`() {
    val targets = mapOf(
      "bill-feature-task" to target("bill-feature-task", internalFor = "   "),
    )
    val error = assertFailsWith<InvalidInternalSkillClassificationError> {
      validateInternalSkillClassification(targets)
    }
    assertMessageNames(error, "bill-feature-task", "empty value")
  }

  @Test
  fun `classification fails when parent is the skill itself`() {
    val targets = mapOf(
      "bill-feature-task" to target("bill-feature-task", internalFor = "bill-feature-task"),
    )
    val error = assertFailsWith<InvalidInternalSkillClassificationError> {
      validateInternalSkillClassification(targets)
    }
    assertMessageNames(error, "bill-feature-task", "skill itself")
  }

  @Test
  fun `classification fails when parent is not a discovered skill`() {
    val targets = mapOf(
      "bill-feature-task" to target("bill-feature-task", internalFor = "bill-featur"),
    )
    val error = assertFailsWith<InvalidInternalSkillClassificationError> {
      validateInternalSkillClassification(targets)
    }
    assertMessageNames(error, "bill-feature-task", "not a discovered skill")
  }

  @Test
  fun `classification fails when parent is itself internal`() {
    // `bill-other` is a discovered listed skill so `bill-feature` (internal-for: bill-other) is a
    // valid internal skill; the only violation is `bill-feature-task` chaining onto `bill-feature`.
    val targets = mapOf(
      "bill-other" to target("bill-other", internalFor = null),
      "bill-feature" to target("bill-feature", internalFor = "bill-other"),
      "bill-feature-task" to target("bill-feature-task", internalFor = "bill-feature"),
    )
    val error = assertFailsWith<InvalidInternalSkillClassificationError> {
      validateInternalSkillClassification(targets)
    }
    assertMessageNames(error, "bill-feature-task", "chained internal-for")
  }

  private fun target(skillName: String, internalFor: String?): AuthoringTarget = AuthoringTarget(
    skillName = skillName,
    packageName = "base",
    platform = "",
    displayName = skillName,
    family = "",
    area = "",
    skillFile = Path.of("/repo/skills/$skillName/SKILL.md"),
    contentFile = Path.of("/repo/skills/$skillName/content.md"),
    internalFor = internalFor,
  )

  private fun writeTempContent(text: String): Path {
    val dir = Files.createTempDirectory("skillbill-internal-classification").also(tempDirs::add)
    val file = dir.resolve("content.md")
    Files.writeString(file, text)
    return file
  }

  private fun assertMessageNames(
    error: InvalidInternalSkillClassificationError,
    skillName: String,
    ruleFragment: String,
  ) {
    val message = error.message.orEmpty()
    assertTrue(message.contains("'$skillName'"), "expected message to name skill '$skillName'; got: $message")
    assertTrue(
      message.contains(ruleFragment),
      "expected message to mention rule fragment '$ruleFragment'; got: $message",
    )
  }
}

package skillbill.scaffold

import skillbill.error.SkillBillRuntimeException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Integration coverage for SKILL-40 subtask 1 fail-fast guards routed through the public
 * [AuthoringOperations] entry points. The helper-level coverage in [AuthoringContentMutationTest]
 * does not catch a regression that swallows the exception in the public entry point or that
 * runs `mutateContent` BEFORE the coerce guard (corrupting content.md before failing).
 *
 * Both regressions are pinned by asserting that:
 *   1. fill() throws SkillBillRuntimeException with the canonical fail-fast message.
 *   2. The on-disk content.md bytes are unchanged (no partial write).
 */
class AuthoringOperationsTest {
  @Test
  fun `fill fails fast and leaves content_md untouched when neither supplied nor existing carries frontmatter`() {
    val repo = seedFillFixtureRepo("fill-no-frontmatter")
    val skillName = "bill-fill-fixture"
    val contentFile = repo.resolve("skills").resolve(skillName).resolve("content.md")
    // Strip the frontmatter to simulate a pre-migration partial state.
    val unframedContent = "# Fixture Content\n\nBody without frontmatter to trip the F-D guard.\n"
    Files.writeString(contentFile, unframedContent)
    val before = Files.readAllBytes(contentFile)

    val error = assertFailsWith<SkillBillRuntimeException> {
      AuthoringOperations.fill(
        repoRoot = repo,
        skillName = skillName,
        body = "Replacement body, also without frontmatter — should never write.",
        sectionName = null,
      )
    }

    assertContains(error.message.orEmpty(), "content.md must already carry a YAML frontmatter block")
    val after = Files.readAllBytes(contentFile)
    assertEquals(before.toList(), after.toList(), "content.md must be untouched when fill fails fast")
  }

  // -------------------------------------------------------------------------------------------
  // F-T3 (testing): pin the public statusPayload key contract so the SKILL-40 removal of
  // `generation_drift` (and the camelCase `hasGenerationDrift` variant) cannot silently
  // regress — and so any future addition or removal trips this test and forces an explicit
  // decision.
  // -------------------------------------------------------------------------------------------

  @Test
  fun `show payload pins exact key set and excludes generation_drift`() {
    val repo = seedFillFixtureRepo("show-keyset")
    val skillName = "bill-fill-fixture"

    val payload = AuthoringOperations.show(repo, skillName, contentMode = "none")

    val expectedKeys = setOf(
      "skill_name",
      "package",
      "platform",
      "family",
      "area",
      "content_file",
      "render_command",
      "completion_status",
      "section_count",
      "sections",
      "recommended_commands",
    )
    assertEquals(expectedKeys, payload.keys, "show payload key set drifted; update F-T3 contract intentionally")

    // Defense in depth: even if the strict equality is loosened in the future, these specific
    // keys must never come back without an explicit decision.
    listOf("generation_drift", "hasGenerationDrift").forEach { key ->
      assertEquals(false, payload.containsKey(key), "Forbidden payload key '$key' is present")
    }
  }

  @Test
  fun `list payload skill entries exclude generation_drift`() {
    val repo = seedFillFixtureRepo("list-keyset")
    val skillName = "bill-fill-fixture"

    val payload = AuthoringOperations.list(repo, listOf(skillName))

    @Suppress("UNCHECKED_CAST")
    val skills = payload["skills"] as List<Map<String, Any?>>

    assertEquals(1, skills.size)
    listOf("generation_drift", "hasGenerationDrift").forEach { key ->
      assertEquals(false, skills.first().containsKey(key), "Forbidden payload key '$key' is present in list output")
    }
  }

  @Test
  fun `validate payload excludes generation_drift in repo and selected modes`() {
    val repo = seedFillFixtureRepo("validate-keyset")
    val skillName = "bill-fill-fixture"

    val repoPayload = AuthoringOperations.validate(repo, emptyList())
    val selectedPayload = AuthoringOperations.validate(repo, listOf(skillName))

    listOf("generation_drift", "hasGenerationDrift").forEach { key ->
      assertEquals(false, repoPayload.containsKey(key), "Forbidden key '$key' present in validate(repo)")
      assertEquals(false, selectedPayload.containsKey(key), "Forbidden key '$key' present in validate(selected)")
    }
  }

  /**
   * F-C is enforced inside [renderContentBody], which is reached via the public [scaffold] entry
   * point through the `content_body` payload field (NOT via [AuthoringOperations.fill], which
   * intentionally accepts a caller-supplied frontmatter block and uses it verbatim through
   * [coerceFullContentText]). Pin the integration contract that scaffold() propagates the F-C
   * exception untouched and leaves no skill directory behind.
   */
  @Test
  fun `scaffold propagates F-C error and leaves no partial skill when content_body carries frontmatter`() {
    val repo = Files.createTempDirectory("skillbill-scaffold-stacked-frontmatter")
    supportingFileTargets(repo).values.forEach { target ->
      Files.createDirectories(target.parent)
      Files.writeString(target, "# ${target.fileName}\n")
    }
    val skillName = "bill-scaffold-stacked-fm"
    val skillDir = repo.resolve("skills").resolve(skillName)
    val bodyWithFrontmatter = """
      |---
      |name: $skillName
      |description: Pre-supplied frontmatter that would stack with the canonical block.
      |---
      |
      |# Body
      |
      |Body with caller-supplied frontmatter to trip the F-C guard.
    """.trimMargin() + "\n"

    val error = assertFailsWith<SkillBillRuntimeException> {
      scaffold(
        mapOf(
          "scaffold_payload_version" to "1.0",
          "kind" to "horizontal",
          "repo_root" to repo.toString(),
          "name" to skillName,
          "content_body" to bodyWithFrontmatter,
        ),
      )
    }

    assertContains(error.message.orEmpty(), "frontmatter")
    // Rollback contract: a failed scaffold must not leave the skill directory behind.
    assertEquals(false, Files.exists(skillDir), "Failed scaffold left a partial skill directory at $skillDir")
  }

  @Test
  fun `fill accepts clean authored body without generated wrapper headings`() {
    val repo = seedFillFixtureRepo("fill-clean-body")
    val skillName = "bill-fill-fixture"
    val contentFile = repo.resolve("skills").resolve(skillName).resolve("content.md")

    val payload =
      AuthoringOperations.fill(
        repoRoot = repo,
        skillName = skillName,
        body = "Clean authored guidance without wrapper headings.",
        sectionName = null,
      )
    val content = Files.readString(contentFile)

    assertEquals("complete", payload["completion_status"])
    assertContains(content, "# Fixture Content\n\nClean authored guidance without wrapper headings.")
    assertEquals(false, "## Descriptor" in content)
    assertEquals(false, "## Execution" in content)
    assertEquals(false, "## Ceremony" in content)
  }

  @Test
  fun `fill rejects generated wrapper headings and rolls back content_md`() {
    val repo = seedFillFixtureRepo("fill-reject-wrapper-heading")
    val skillName = "bill-fill-fixture"
    val contentFile = repo.resolve("skills").resolve(skillName).resolve("content.md")
    val before = Files.readString(contentFile)

    val error = assertFailsWith<SkillBillRuntimeException> {
      AuthoringOperations.fill(
        repoRoot = repo,
        skillName = skillName,
        body = "## Execution\n\nGenerated wrapper content must not be authored here.",
        sectionName = null,
      )
    }

    assertContains(error.message.orEmpty(), "generated wrapper boilerplate heading '## Execution'")
    assertEquals(before, Files.readString(contentFile), "content.md must roll back after wrapper-heading rejection")
  }

  @Test
  fun `edit with section targets authored H2 sections only`() {
    val repo = seedSectionFixtureRepo("edit-authored-section")
    val skillName = "bill-section-fixture"
    val contentFile = repo.resolve("skills").resolve(skillName).resolve("content.md")

    val payload =
      AuthoringOperations.editWithBodyFile(
        repoRoot = repo,
        skillName = skillName,
        body = "Updated guidance body.",
        sectionName = "Review Guidance",
      )
    val content = Files.readString(contentFile)

    assertEquals("Review Guidance", payload["updated_section"])
    assertContains(content, "## Review Guidance\n\nUpdated guidance body.")
    assertContains(content, "## Review Focus")
    assertContains(content, "Initial focus.")
  }

  @Test
  fun `edit with generated wrapper section explains authored surface instead of mutating`() {
    val repo = seedSectionFixtureRepo("edit-generated-section")
    val skillName = "bill-section-fixture"
    val contentFile = repo.resolve("skills").resolve(skillName).resolve("content.md")
    val before = Files.readString(contentFile)

    val error = assertFailsWith<SkillBillRuntimeException> {
      AuthoringOperations.editWithBodyFile(
        repoRoot = repo,
        skillName = skillName,
        body = "New generated section body.",
        sectionName = "Descriptor",
      )
    }

    val message = error.message.orEmpty()
    assertContains(message, "Cannot edit generated wrapper section '## Descriptor'")
    assertContains(message, "Edit authored content.md sections")
    assertContains(message, "platform.yaml manifest fields")
    assertEquals(before, Files.readString(contentFile), "content.md must not change for generated-section edits")
  }

  private fun seedFillFixtureRepo(prefix: String): Path {
    val repo = Files.createTempDirectory("skillbill-$prefix")
    val skillName = "bill-fill-fixture"
    val skillDir = repo.resolve("skills").resolve(skillName)
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: $skillName
      description: Fixture skill for AuthoringOperations integration tests.
      ---

      # Fixture Content

      Initial authored content.
      """.trimIndent() + "\n",
    )
    return repo
  }

  private fun seedSectionFixtureRepo(prefix: String): Path {
    val repo = Files.createTempDirectory("skillbill-$prefix")
    val skillName = "bill-section-fixture"
    val skillDir = repo.resolve("skills").resolve(skillName)
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: $skillName
      description: Fixture skill for AuthoringOperations section tests.
      ---

      # Fixture Content

      ## Review Focus

      Initial focus.

      ## Review Guidance

      Initial guidance.
      """.trimIndent() + "\n",
    )
    return repo
  }
}

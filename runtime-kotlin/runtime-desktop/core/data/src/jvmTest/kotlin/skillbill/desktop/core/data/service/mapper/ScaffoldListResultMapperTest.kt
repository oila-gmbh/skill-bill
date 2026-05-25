package skillbill.desktop.core.data.service.mapper

import skillbill.ports.scaffold.catalog.model.ScaffoldListResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SKILL-52.2 subtask 5 (review-fix F-002) — unit coverage for
 * [authoredSkillEntries]. The mapper consumes the typed
 * [ScaffoldListResult] but still decodes the documented `@OpenBoundaryMap`
 * `payload` field's `skills` array. The three failure shapes (missing key,
 * wrong type, missing `skill_name` row) silently degrade to `emptyList()` /
 * dropped row, and the caller (`RuntimeRepoBrowserService.loadAuthoredSkills`)
 * depends on blank vs non-blank `platform` propagating verbatim. These tests
 * pin that behavior so future drift loud-fails.
 */
class ScaffoldListResultMapperTest {
  @Test
  fun `typical payload decodes all entries verbatim across every typed field`() {
    val result = scaffoldListResult(
      skillCount = 2,
      skills = listOf(
        mapOf(
          "skill_name" to "alpha",
          "platform" to "kotlin",
          "family" to "code-review",
          "area" to "architecture",
          "content_file" to "platform-packs/kotlin/code-review/alpha/SKILL.md",
          "completion_status" to "complete",
          "package" to "skillbill.kotlin.codereview.alpha",
        ),
        mapOf(
          "skill_name" to "beta",
          "platform" to "",
          "family" to "",
          "area" to "",
          "content_file" to "skills/beta/SKILL.md",
          "completion_status" to "draft",
          "package" to null,
        ),
      ),
    )

    val entries = result.authoredSkillEntries()

    assertEquals(2, entries.size)
    assertEquals(
      AuthoredSkillEntry(
        skillName = "alpha",
        platform = "kotlin",
        family = "code-review",
        area = "architecture",
        contentFile = "platform-packs/kotlin/code-review/alpha/SKILL.md",
        completionStatus = "complete",
        packageName = "skillbill.kotlin.codereview.alpha",
      ),
      entries[0],
    )
    assertEquals(
      AuthoredSkillEntry(
        skillName = "beta",
        platform = "",
        family = "",
        area = "",
        contentFile = "skills/beta/SKILL.md",
        completionStatus = "draft",
        packageName = null,
      ),
      entries[1],
    )
  }

  @Test
  fun `missing skills key returns empty list without throwing`() {
    val result = ScaffoldListResult(
      repoRoot = "/repo",
      skillCount = 0,
      payload = mapOf("repo_root" to "/repo", "skill_count" to 0),
    )

    assertEquals(emptyList(), result.authoredSkillEntries())
  }

  @Test
  fun `wrong type for skills payload key returns empty list without throwing`() {
    // The legacy wire-shape contract is `skills: List<Map<String, Any?>>`. If
    // a future producer regression hands back a string, a number, or a map,
    // the mapper must silently degrade instead of throwing on the cast.
    val stringCase = scaffoldListResult(skillCount = 0, skillsRaw = "not-a-list")
    val mapCase = scaffoldListResult(skillCount = 0, skillsRaw = mapOf("alpha" to "x"))
    val intCase = scaffoldListResult(skillCount = 0, skillsRaw = 7)

    assertEquals(emptyList(), stringCase.authoredSkillEntries())
    assertEquals(emptyList(), mapCase.authoredSkillEntries())
    assertEquals(emptyList(), intCase.authoredSkillEntries())
  }

  @Test
  fun `row entries missing skill_name are dropped and remaining rows retained`() {
    val result = scaffoldListResult(
      skillCount = 1,
      skills = listOf(
        mapOf(
          "platform" to "kotlin",
          "family" to "code-review",
          // intentionally no `skill_name`
        ),
        mapOf(
          "skill_name" to "kept",
          "platform" to "kotlin",
        ),
        mapOf(
          "skill_name" to null,
          "platform" to "kotlin",
        ),
      ),
    )

    val entries = result.authoredSkillEntries()

    assertEquals(1, entries.size)
    assertEquals("kept", entries.single().skillName)
    assertEquals("kotlin", entries.single().platform)
  }

  @Test
  fun `blank vs non-blank platform propagates verbatim so the caller can branch on it`() {
    // `RuntimeRepoBrowserService.loadAuthoredSkills` uses the empty vs
    // non-empty platform string to switch between horizontal vs platform-pack
    // rendering. The mapper must NOT collapse blank to null or vice versa.
    val result = scaffoldListResult(
      skillCount = 2,
      skills = listOf(
        mapOf("skill_name" to "horizontal", "platform" to ""),
        mapOf("skill_name" to "packed", "platform" to "kotlin"),
      ),
    )

    val entries = result.authoredSkillEntries()
    val byName = entries.associateBy { it.skillName }

    assertEquals("", byName.getValue("horizontal").platform)
    assertEquals("kotlin", byName.getValue("packed").platform)
    assertTrue(byName.getValue("horizontal").platform.isEmpty())
    assertTrue(byName.getValue("packed").platform.isNotEmpty())
  }

  private fun scaffoldListResult(skillCount: Int, skills: List<Map<String, Any?>>): ScaffoldListResult =
    scaffoldListResult(skillCount = skillCount, skillsRaw = skills)

  private fun scaffoldListResult(skillCount: Int, skillsRaw: Any?): ScaffoldListResult = ScaffoldListResult(
    repoRoot = "/repo",
    skillCount = skillCount,
    payload = mapOf(
      "repo_root" to "/repo",
      "skill_count" to skillCount,
      "skills" to skillsRaw,
    ),
  )
}

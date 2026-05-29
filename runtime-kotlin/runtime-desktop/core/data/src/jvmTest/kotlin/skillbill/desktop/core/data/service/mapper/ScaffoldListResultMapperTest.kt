package skillbill.desktop.core.data.service.mapper

import skillbill.ports.scaffold.catalog.model.ScaffoldListResult
import skillbill.ports.scaffold.model.ScaffoldSkillStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SKILL-52.3 subtask 3 — unit coverage for [authoredSkillEntries].
 *
 * SKILL-52.3 subtask 3 retired the `@OpenBoundaryMap` `payload` field on
 * [ScaffoldListResult]; the mapper now collapses to a 1:1 copy of the typed
 * `List<ScaffoldSkillStatus>`. The legacy raw-shape degradation cases (missing key,
 * wrong type, missing `skill_name` row) are structurally impossible now, so these tests
 * pin the typed copy fidelity and the blank-vs-non-blank `platform` propagation the
 * caller (`RuntimeRepoBrowserService.loadAuthoredSkills`) depends on.
 */
class ScaffoldListResultMapperTest {
  @Test
  fun `typed entries copy verbatim across every field`() {
    val result = scaffoldListResult(
      skills = listOf(
        skillStatus(
          skillName = "alpha",
          platform = "kotlin",
          family = "code-review",
          area = "architecture",
          contentFile = "platform-packs/kotlin/code-review/alpha/SKILL.md",
          completionStatus = "complete",
          packageName = "skillbill.kotlin.codereview.alpha",
        ),
        skillStatus(
          skillName = "beta",
          platform = "",
          family = "",
          area = "",
          contentFile = "skills/beta/SKILL.md",
          completionStatus = "draft",
          packageName = "",
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
        packageName = "",
      ),
      entries[1],
    )
  }

  @Test
  fun `empty skills yields empty entries`() {
    val result = scaffoldListResult(skills = emptyList())

    assertEquals(emptyList(), result.authoredSkillEntries())
  }

  @Test
  fun `blank vs non-blank platform propagates verbatim so the caller can branch on it`() {
    // `RuntimeRepoBrowserService.loadAuthoredSkills` uses the empty vs non-empty platform
    // string to switch between horizontal vs platform-pack rendering. The mapper must NOT
    // collapse blank to null or vice versa.
    val result = scaffoldListResult(
      skills = listOf(
        skillStatus(skillName = "horizontal", platform = ""),
        skillStatus(skillName = "packed", platform = "kotlin"),
      ),
    )

    val entries = result.authoredSkillEntries()
    val byName = entries.associateBy { it.skillName }

    assertEquals("", byName.getValue("horizontal").platform)
    assertEquals("kotlin", byName.getValue("packed").platform)
    assertTrue(byName.getValue("horizontal").platform.isEmpty())
    assertTrue(byName.getValue("packed").platform.isNotEmpty())
  }

  private fun scaffoldListResult(skills: List<ScaffoldSkillStatus>): ScaffoldListResult = ScaffoldListResult(
    repoRoot = "/repo",
    skillCount = skills.size,
    skills = skills,
  )

  private fun skillStatus(
    skillName: String,
    platform: String = "",
    family: String = "",
    area: String = "",
    contentFile: String = "skills/$skillName/SKILL.md",
    completionStatus: String = "draft",
    packageName: String = "",
  ): ScaffoldSkillStatus = ScaffoldSkillStatus(
    skillName = skillName,
    packageName = packageName,
    platform = platform,
    family = family,
    area = area,
    contentFile = contentFile,
    renderCommand = "skill-bill render $skillName",
    completionStatus = completionStatus,
    sectionCount = 0,
    sections = emptyList(),
    recommendedCommands = emptyList(),
  )
}

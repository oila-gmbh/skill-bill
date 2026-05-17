package skillbill.desktop.feature.skillbill.ui

import skillbill.desktop.core.domain.model.RenderBlock
import skillbill.desktop.core.domain.model.RenderRunState
import skillbill.desktop.core.domain.model.RenderSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillBillFrameRenderConsoleTest {

  @Test
  fun `render all console groups blocks by render target`() {
    val render = RenderSummary(
      state = RenderRunState.FAILED,
      blocks = listOf(
        RenderBlock(
          header = "===== render target: Skill One (skill-one) =====",
          content = "state: passed\ngenerated artifacts: 1\n",
        ),
        RenderBlock(
          header = "[Skill One] ===== SKILL.md: skills/one/SKILL.md =====",
          content = "# one\n",
        ),
        RenderBlock(
          header = "===== render target: Skill Two (skill-two) =====",
          content = "state: failed\nexception: IllegalStateException: boom\ngenerated artifacts: 0\n",
        ),
        RenderBlock(
          header = "[Skill Two] ===== SKILL.md: skills/two/SKILL.md =====",
          content = "# two\n",
        ),
      ),
    )

    val sections = buildRenderAllConsoleSections(render)

    assertEquals(2, sections.size)
    assertEquals("Skill One (skill-one)", sections[0].title)
    assertEquals("passed", sections[0].state)
    assertEquals("1", sections[0].generatedArtifacts)
    assertTrue(sections[0].detailLines.any { line -> line.contains("skills/one/SKILL.md") })
    assertTrue(sections[0].detailLines.none { line -> line.contains("skills/two/SKILL.md") })
    assertEquals("Skill Two (skill-two)", sections[1].title)
    assertEquals("failed", sections[1].state)
    assertEquals("IllegalStateException: boom", sections[1].exception)
    assertEquals(listOf("Skill Two (skill-two)"), sections.failedSections().map { section -> section.title })
  }

  @Test
  fun `render all console grouping ignores single target render output`() {
    val render = RenderSummary(
      state = RenderRunState.PASSED,
      blocks = listOf(
        RenderBlock(
          header = "===== SKILL.md: skills/one/SKILL.md =====",
          content = "# one\n",
        ),
      ),
    )

    assertEquals(emptyList(), buildRenderAllConsoleSections(render))
  }
}

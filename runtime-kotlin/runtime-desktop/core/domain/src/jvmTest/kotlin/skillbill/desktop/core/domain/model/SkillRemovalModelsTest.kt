package skillbill.desktop.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SkillRemovalModelsTest {
  @Test
  fun `desktop symlink providers cover every native agent provider including cursor`() {
    assertEquals(
      listOf("CLAUDE", "CODEX", "OPENCODE", "JUNIE", "CURSOR", "ZCODE"),
      DesktopAgentSymlinkProvider.entries.map(DesktopAgentSymlinkProvider::name),
    )
  }
}

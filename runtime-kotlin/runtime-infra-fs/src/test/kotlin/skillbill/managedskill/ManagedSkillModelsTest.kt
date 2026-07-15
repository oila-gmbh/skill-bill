package skillbill.managedskill

import skillbill.managedskill.model.AgentSkillTargetId
import skillbill.managedskill.model.OpaqueSkillBundle
import skillbill.managedskill.model.OpaqueSkillBundleFile
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ManagedSkillModelsTest {
  @Test
  fun `target identity canonicalizes provider and lexical path aliases`() {
    val canonical = AgentSkillTargetId("codex", Path.of("/tmp/targets/codex"))
    assertEquals(canonical, AgentSkillTargetId(" CODEX ", Path.of("/tmp/targets/../targets/codex")))
    assertEquals(1, setOf(canonical, AgentSkillTargetId("Codex", Path.of("/tmp/targets/codex"))).size)
  }

  @Test
  fun `target identity rejects unsafe providers and relative paths`() {
    assertFailsWith<IllegalArgumentException> { AgentSkillTargetId("bad/provider", Path.of("/tmp/codex")) }
    assertFailsWith<IllegalArgumentException> { AgentSkillTargetId("codex", Path.of("relative")) }
  }

  @Test
  fun `bundle bytes and files are immutable from callers`() {
    val source = byteArrayOf(1, 2, 3)
    val file = OpaqueSkillBundleFile("SKILL.md", source)
    source[0] = 9
    val returned = file.content
    returned[1] = 9
    assertContentEquals(byteArrayOf(1, 2, 3), file.content)

    val bundle = OpaqueSkillBundle("sample", "Sample", Path.of("/tmp/SKILL.md"), listOf(file), 3, "a".repeat(64))
    assertFailsWith<UnsupportedOperationException> {
      @Suppress("UNCHECKED_CAST")
      (bundle.files as MutableList<OpaqueSkillBundleFile>).clear()
    }
  }
}

package skillbill.scaffold

import skillbill.scaffold.rendering.renderSubagentSpawnRuntimeNotes
import kotlin.test.Test
import kotlin.test.assertEquals

class SubagentSpawnRuntimeNotesTest {
  @Test
  fun `no specialists renders no spawn notes`() {
    assertEquals("", renderSubagentSpawnRuntimeNotes("bill-parity-orchestrator", emptyList()))
  }
}

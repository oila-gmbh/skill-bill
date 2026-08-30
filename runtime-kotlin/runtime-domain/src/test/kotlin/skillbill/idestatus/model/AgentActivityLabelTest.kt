package skillbill.idestatus.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentActivityLabelTest {
  @Test
  fun `probe sources map to governed wire labels`() {
    assertEquals(AgentActivityLabel.WORKTREE_WRITE, AgentActivityLabel.fromWire("worktree write"))
    assertEquals(AgentActivityLabel.STDOUT, AgentActivityLabel.fromWire("stdout"))
    assertEquals(AgentActivityLabel.DURABLE_PROGRESS, AgentActivityLabel.fromWire("durable progress"))
    assertEquals(AgentActivityLabel.EVIDENCE_READ, AgentActivityLabel.fromWire("evidence read"))
    assertEquals(AgentActivityLabel.TOOL_STREAM, AgentActivityLabel.fromWire("tool stream"))
  }

  @Test
  fun `arbitrary probe text does not escape to wire enum`() {
    assertNull(AgentActivityLabel.normalizeProbeText("Reading file /tmp/foo"))
    assertNull(AgentActivityLabel.normalizeProbeText("grep"))
    assertNull(AgentActivityLabel.fromWire("tool_call"))
  }
}

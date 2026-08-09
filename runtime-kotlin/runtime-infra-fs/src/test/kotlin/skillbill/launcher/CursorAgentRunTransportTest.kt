package skillbill.launcher

import skillbill.launcher.agentrun.AgentRunOutputDecoder
import skillbill.launcher.agentrun.CursorAgentRunCommandBuilder
import skillbill.ports.agentrun.model.SkillRunRequest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Cursor's transport choice, kept apart from the builder matrix because it encodes one rule: a
 * launch nobody streams must not pay for the whole-session transport.
 */
class CursorAgentRunTransportTest {
  @Test
  fun `unstreamed launch buffers instead of carrying the whole session`() {
    val command = CursorAgentRunCommandBuilder().build(request())

    assertFalse(
      command.command.contains("stream-json"),
      "a launch nobody streams must not request the whole-session transport; its terminal event " +
        "arrives last, so a capped drain spends its budget on the preamble",
    )
    assertTrue(command.command.contains("json"))
  }

  @Test
  fun `a streamed launch still requests the per-event transport`() {
    val command = CursorAgentRunCommandBuilder().build(request().copy(streamOutputForLiveness = true))

    assertTrue(
      command.command.contains("stream-json"),
      "liveness needs per-event output to prove the launch is working",
    )
  }

  @Test
  fun `the decoder harvests the buffered single-object form the CLI emits`() {
    val decoded = AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(
      """{"type":"result","subtype":"success","is_error":false,"result":"PONG",""" +
        """"usage":{"inputTokens":14154,"outputTokens":14,"cacheReadTokens":896}}""",
    )

    assertEquals("PONG", decoded.text)
    assertEquals(14154L, decoded.inputTokens)
    assertEquals(14L, decoded.outputTokens)
  }

  private fun request(): SkillRunRequest = SkillRunRequest(
    issueKey = "SKILL-113",
    repoRoot = Path.of("/tmp/skillbill-agent-run"),
    subtaskId = 1,
    timeout = 3.seconds,
    promptOverride = "Phase: implement",
  )
}

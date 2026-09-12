package skillbill.infrastructure.fs.launcher.agentrun

import skillbill.contracts.time.JvmSystemClock
import skillbill.error.AuditRepairProviderSessionError
import skillbill.infrastructure.fs.launcher.process.JvmAgentRunProcessRunner
import skillbill.infrastructure.fs.launcher.testAgentRunProcessRequest
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.SkillRunRequest
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AuditProviderSessionTest {
  @Test
  fun `chunked initialization records once and payload session fields cannot bind a launch`() {
    val recorded = mutableListOf<String>()
    val sink =
      ProviderSessionOutputSink(AgentRunOutputDecoder.CODEX_JSONL, recorded::add, AgentRunOutputSink { _, _ -> })
    val stream = """{"type":"item.completed","item":{"thread_id":"fake"}}""" + "\n" +
      """{"type":"thread.started","thread_id":"actual"}"""
    stream.chunked(7).forEach { sink.write(AgentRunOutputStream.STDOUT, it) }
    sink.finish()
    assertEquals(listOf("actual"), recorded)
    assertEquals("actual", sink.requireSessionId())
    assertFailsWith<AuditRepairProviderSessionError> {
      sink.write(AgentRunOutputStream.STDOUT, """{"type":"thread.started","thread_id":"replacement"}""" + "\n")
    }
    assertEquals(listOf("actual"), recorded)
  }

  @Test
  fun `identity persistence failure terminates the owned process before returning failure`() {
    val pid = AtomicLong()
    val sink = ProviderSessionOutputSink(
      AgentRunOutputDecoder.CODEX_JSONL,
      { identity ->
        pid.set(identity.toLong())
        error("injected persistence failure")
      },
      AgentRunOutputSink { _, _ -> },
    )
    val script = "import os,json,time; print(json.dumps({'type':'thread.started','thread_id':str(os.getpid())})," +
      "flush=True); time.sleep(60)"
    val request = testAgentRunProcessRequest(listOf("python3", "-c", script), Path.of(".")) {
      outputSink = sink
      timeout = 10.seconds
    }
    assertFailsWith<AuditRepairProviderSessionError> { JvmAgentRunProcessRunner(JvmSystemClock).run(request) }
    assertTrue(pid.get() > 0)
    assertFalse(ProcessHandle.of(pid.get()).map { it.isAlive }.orElse(false))
  }

  @Test
  fun `audit continuation uses noninteractive codex and forces claude initialization streaming`() {
    val request = SkillRunRequest(
      issueKey = "SKILL-240",
      promptOverride = "Resume the existing audit.",
      repoRoot = Path.of("."),
      timeout = 10.seconds,
      auditRepairExecutionId = "execution",
      auditRepairSessionId = "session",
      auditRepairResume = true,
    )
    val codex = CodexAgentRunCommandBuilder().build(request)
    assertEquals(listOf("codex", "exec", "resume", "session"), codex.command.take(4))
    assertTrue("--json" in codex.command)
    assertFalse("--cd" in codex.command)
    assertEquals("-", codex.command.last())
    assertEquals(request.repoRoot, codex.workingDirectory)
    val claude = ClaudeAgentRunCommandBuilder().build(request)
    assertEquals("stream-json", claude.command[claude.command.indexOf("--output-format") + 1])
    assertEquals(
      "session",
      claude.outputDecoder?.sessionStarted(
        """{"type":"system","subtype":"init","session_id":"session"}""",
      ),
    )
  }
}

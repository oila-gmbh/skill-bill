package dev.skillbill.intellij.infrastructure.cli

import dev.skillbill.intellij.domain.IDE_STATUS_CONTRACT_VERSION
import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import dev.skillbill.intellij.domain.UnavailableReason
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessRunnerTest {
    @Test
    fun `timeout returns timedOut without exposing stderr`() = runBlocking {
        val runner = ProcessRunner(
            processFactory = ProcessFactory { _, _ ->
                SlowProcess(hangMs = 5_000, stderr = "SECRET_STDERR /home/user/secret")
            },
        )
        val result = runner.runCoalesced(
            ProcessSpec(
                command = listOf("skill-bill"),
                timeoutMs = 50,
                stdoutLimitBytes = 1_024,
                stderrLimitBytes = 1_024,
            ),
        )
        assertTrue(result.timedOut)
        assertFalse(result.stdout.contains("SECRET"))
        assertFalse(result.stdout.contains("/home/user"))
    }

    @Test
    fun `cancellation destroys in-flight process`() = runBlocking {
        val started = CountDownLatch(1)
        val runner = ProcessRunner(
            processFactory = ProcessFactory { _, _ ->
                SlowProcess(hangMs = 10_000, onStart = { started.countDown() })
            },
        )
        val job = async {
            runner.runCoalesced(
                ProcessSpec(
                    command = listOf("skill-bill"),
                    timeoutMs = 5_000,
                    stdoutLimitBytes = 1_024,
                    stderrLimitBytes = 1_024,
                ),
            )
        }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        runner.cancelAll()
        val result = job.await()
        assertTrue(result.cancelled || result.timedOut)
    }

    @Test
    fun `overlapping polls coalesce to one process`() = runBlocking {
        val starts = AtomicInteger(0)
        val release = CountDownLatch(1)
        val runner = ProcessRunner(
            processFactory = ProcessFactory { _, _ ->
                starts.incrementAndGet()
                GatedProcess(release, stdout = """{"ok":true}""", exitCode = 0)
            },
        )
        val first = async {
            runner.runCoalesced(
                ProcessSpec(listOf("x"), timeoutMs = 2_000, stdoutLimitBytes = 1_024, stderrLimitBytes = 256),
            )
        }
        delay(30)
        val second = async {
            runner.runCoalesced(
                ProcessSpec(listOf("x"), timeoutMs = 2_000, stdoutLimitBytes = 1_024, stderrLimitBytes = 256),
            )
        }
        delay(30)
        release.countDown()
        first.await()
        second.await()
        assertEquals(1, starts.get())
    }

    @Test
    fun `non-zero exit is surfaced as exitCode`() = runBlocking {
        val runner = ProcessRunner(
            processFactory = ProcessFactory { _, _ ->
                ImmediateProcess(stdout = "", exitCode = 2)
            },
        )
        val result = runner.runCoalesced(
            ProcessSpec(listOf("x"), timeoutMs = 1_000, stdoutLimitBytes = 1_024, stderrLimitBytes = 256),
        )
        assertEquals(2, result.exitCode)
    }
}

class IdeStatusJsonMapperTest {
    private val now = java.time.Instant.parse("2026-08-06T10:00:00Z")

    @Test
    fun `maps schema-valid active runtime fixture`() {
        val json = fixture("active-runtime.json")
        val outcome = IdeStatusJsonMapper.map(json, now, exitCode = 0)
        assertTrue(outcome is SkillBillStatusOutcome.Active)
        outcome as SkillBillStatusOutcome.Active
        assertEquals("SKILL-148", outcome.issueKey)
        assertEquals("implement", outcome.currentStepId)
        assertFalse(outcome.summary.contains("/home/"))
    }

    @Test
    fun `maps goal fixture with subtask timestamps`() {
        val outcome = IdeStatusJsonMapper.map(fixture("active-goal.json"), now, 0)
        assertTrue(outcome is SkillBillStatusOutcome.Active)
        outcome as SkillBillStatusOutcome.Active
        assertEquals("2", outcome.currentSubtaskId)
        assertEquals(java.time.Instant.parse("2026-08-06T09:00:00Z"), outcome.subtaskStartedAt)
    }

    @Test
    fun `incompatible contract version`() {
        val json = """{"contract_version":"9.9","repository_identity":"r","lifecycle_state":"idle","current_step":{"id":"none","label":"n"},"updated_at":"2026-08-06T10:00:00Z","freshness":"unknown","summary":"x"}"""
        val outcome = IdeStatusJsonMapper.map(json, now, 0)
        assertTrue(outcome is SkillBillStatusOutcome.Incompatible)
    }

    @Test
    fun `malformed JSON becomes unavailable`() {
        val outcome = IdeStatusJsonMapper.map("{not-json", now, 0)
        assertTrue(outcome is SkillBillStatusOutcome.Unavailable)
        assertEquals(
            UnavailableReason.MALFORMED_OUTPUT,
            (outcome as SkillBillStatusOutcome.Unavailable).reasonCode,
        )
    }

    @Test
    fun `process failure exit code maps without leaking stderr paths`() {
        val outcome = IdeStatusJsonMapper.map("trace /home/user/secret", now, exitCode = 7)
        assertTrue(outcome is SkillBillStatusOutcome.Unavailable)
        assertFalse(outcome.toString().contains("/home/user"))
        assertEquals(7, outcome.diagnostic?.exitCode)
    }

    @Test
    fun `stale freshness maps to stale`() {
        val json = fixture("active-runtime.json").replace("\"fresh\"", "\"stale\"")
        val outcome = IdeStatusJsonMapper.map(json, now, 0)
        assertTrue(outcome is SkillBillStatusOutcome.Stale)
    }

    @Test
    fun `expected contract version constant matches fixture`() {
        assertTrue(fixture("active-runtime.json").contains("\"contract_version\": \"$IDE_STATUS_CONTRACT_VERSION\""))
    }

    private fun fixture(name: String): String =
        javaClass.classLoader.getResourceAsStream("fixtures/$name")!!
            .readBytes()
            .toString(StandardCharsets.UTF_8)
}

private class SlowProcess(
    private val hangMs: Long,
    private val stderr: String = "",
    private val onStart: () -> Unit = {},
) : ProcessHandle {
    @Volatile private var alive = true

    init {
        onStart()
    }

    override val inputStream: InputStream = ByteArrayInputStream(ByteArray(0))
    override val errorStream: InputStream = ByteArrayInputStream(stderr.toByteArray())
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        val ms = unit.toMillis(timeout).coerceAtMost(hangMs)
        Thread.sleep(ms)
        return ms >= hangMs
    }
    override fun destroyForcibly() {
        alive = false
    }
    override fun exitValue(): Int = 0
    override fun isAlive(): Boolean = alive
}

private class GatedProcess(
    private val release: CountDownLatch,
    private val stdout: String,
    private val exitCode: Int,
) : ProcessHandle {
    override val inputStream: InputStream = ByteArrayInputStream(stdout.toByteArray())
    override val errorStream: InputStream = ByteArrayInputStream(ByteArray(0))
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        release.await(timeout, unit)
        return true
    }
    override fun destroyForcibly() {}
    override fun exitValue(): Int = exitCode
    override fun isAlive(): Boolean = false
}

private class ImmediateProcess(
    private val stdout: String,
    private val exitCode: Int,
) : ProcessHandle {
    override val inputStream: InputStream = ByteArrayInputStream(stdout.toByteArray())
    override val errorStream: InputStream = ByteArrayInputStream(ByteArray(0))
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
    override fun destroyForcibly() {}
    override fun exitValue(): Int = exitCode
    override fun isAlive(): Boolean = false
}

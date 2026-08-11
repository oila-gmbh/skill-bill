package dev.skillbill.intellij.infrastructure.cli

import dev.skillbill.intellij.domain.IDE_STATUS_CONTRACT_VERSION
import dev.skillbill.intellij.domain.NO_MATCHING_WORK_REASON_CODE
import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import dev.skillbill.intellij.domain.UnavailableReason
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // Dispatch off the runBlocking event loop so CountDownLatch.await cannot starve start.
        val job = async(Dispatchers.IO) {
            runner.runCoalesced(
                ProcessSpec(
                    command = listOf("skill-bill"),
                    timeoutMs = 5_000,
                    stdoutLimitBytes = 1_024,
                    stderrLimitBytes = 1_024,
                ),
            )
        }
        assertTrue(withContext(Dispatchers.IO) { started.await(2, TimeUnit.SECONDS) })
        runner.cancelAll()
        val result = job.await()
        assertTrue(result.cancelled)
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

private const val VALID_PLANNING =
    """"planning": {"state": "partially_planned", "shared_preplan_prepared": true,
       "planned_subtask_count": 1, "total_subtask_count": 4,
       "current_planning_subtask_id": "subtask-2"}"""

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
        val outcome = IdeStatusJsonMapper.map(runtimeFixture(freshness = "stale"), now, 0)
        assertTrue(outcome is SkillBillStatusOutcome.Stale)
    }

    @Test
    fun `stale freshness does not mask a blocked lifecycle`() {
        val json = runtimeFixture(lifecycle = "blocked", freshness = "stale")
        val outcome = IdeStatusJsonMapper.map(json, now, 0)
        assertTrue(outcome is SkillBillStatusOutcome.Blocked)
        assertTrue((outcome as SkillBillStatusOutcome.Blocked).stale)
    }

    @Test
    fun `stale freshness does not resurrect terminal work as an execution`() {
        val json = runtimeFixture(lifecycle = "terminal", freshness = "stale")
        val outcome = IdeStatusJsonMapper.map(json, now, 0)
        assertTrue(outcome is SkillBillStatusOutcome.Done)
        // The lifecycle wins, but the stale reading still has to reach the surface.
        assertTrue((outcome as SkillBillStatusOutcome.Done).stale)
    }

    @Test
    fun `fresh terminal work maps to done with its issue key and progress`() {
        val outcome = IdeStatusJsonMapper.map(runtimeFixture(lifecycle = "terminal"), now, 0)
        assertTrue(outcome is SkillBillStatusOutcome.Done)
        outcome as SkillBillStatusOutcome.Done
        assertFalse(outcome.stale)
        assertEquals("SKILL-148", outcome.issueKey)
    }

    @Test
    fun `no matching work is idle rather than unavailable`() {
        val json = """
            {"contract_version":"$IDE_STATUS_CONTRACT_VERSION","repository_identity":"repo",
             "lifecycle_state":"idle","current_step":{"id":"none","label":"No matching work"},
             "freshness":"fresh","updated_at":"2026-08-07T06:15:10Z",
             "summary":"No matching Skill Bill work for this repository.",
             "problem":{"code":"no_matching_work","message":"No matching Skill Bill work for this repository."}}
        """.trimIndent()
        val outcome = IdeStatusJsonMapper.map(json, now, 0)
        assertTrue(outcome is SkillBillStatusOutcome.Idle)
        outcome as SkillBillStatusOutcome.Idle
        assertEquals(NO_MATCHING_WORK_REASON_CODE, outcome.diagnostic?.reasonCode)
        assertEquals("No matching Skill Bill work for this repository.", outcome.summary)
        assertEquals("repo", outcome.repositoryIdentity)
    }

    @Test
    fun `lifecycle idle maps to idle without the unconfirmed marker`() {
        val json = """
            {"contract_version":"$IDE_STATUS_CONTRACT_VERSION","repository_identity":"repo",
             "lifecycle_state":"idle","freshness":"fresh","summary":"Nothing running."}
        """.trimIndent()
        val outcome = IdeStatusJsonMapper.map(json, now, 0)
        assertTrue(outcome is SkillBillStatusOutcome.Idle)
        assertNull((outcome as SkillBillStatusOutcome.Idle).diagnostic)
    }

    @Test
    fun `lifecycle terminal keeps its outcome and carries no marker`() {
        val outcome = IdeStatusJsonMapper.map(runtimeFixture(lifecycle = "terminal"), now, 0)
        assertTrue(outcome is SkillBillStatusOutcome.Done)
        assertNull((outcome as SkillBillStatusOutcome.Done).diagnostic)
    }

    @Test
    fun `an unrelated problem code is unaffected by the marker`() {
        val json = """
            {"contract_version":"$IDE_STATUS_CONTRACT_VERSION","repository_identity":"repo",
             "lifecycle_state":"idle","freshness":"fresh","summary":"No database.",
             "problem":{"code":"absent_database","message":"No database."}}
        """.trimIndent()
        val outcome = IdeStatusJsonMapper.map(json, now, 0)
        assertTrue(outcome is SkillBillStatusOutcome.Unavailable)
        outcome as SkillBillStatusOutcome.Unavailable
        assertEquals(UnavailableReason.ABSENT_DATABASE, outcome.reasonCode)
        assertEquals("absent_database", outcome.diagnostic?.reasonCode)
    }

    @Test
    fun `goal payload carrying planning maps into the outcome`() {
        val outcome = IdeStatusJsonMapper.map(goalPayload(planning = VALID_PLANNING), now, 0)
        assertTrue(outcome is SkillBillStatusOutcome.Active)
        val planning = (outcome as SkillBillStatusOutcome.Active).planning
        assertEquals("partially_planned", planning?.state)
        assertEquals(true, planning?.sharedPreplanPrepared)
        assertEquals(1, planning?.plannedSubtaskCount)
        assertEquals(4, planning?.totalSubtaskCount)
        assertEquals("subtask-2", planning?.currentPlanningSubtaskId)
    }

    @Test
    fun `payload without planning maps exactly as today`() {
        val outcome = IdeStatusJsonMapper.map(goalPayload(planning = null), now, 0)
        assertTrue(outcome is SkillBillStatusOutcome.Active)
        outcome as SkillBillStatusOutcome.Active
        assertNull(outcome.planning)
        assertEquals("SKILL-165", outcome.issueKey)
        assertEquals("implement", outcome.currentStepId)
        assertEquals(1, outcome.progressCompleted)
        assertEquals(4, outcome.progressTotal)
    }

    @Test
    fun `goal payload carrying a current model maps model and effort onto the outcome`() {
        val outcome = IdeStatusJsonMapper.map(
            goalPayload(planning = null, currentModel = """"current_model": {"model": "opus-5", "effort": "high"}"""),
            now,
            0,
        )
        assertTrue(outcome is SkillBillStatusOutcome.Active)
        val currentModel = (outcome as SkillBillStatusOutcome.Active).currentModel
        assertEquals("opus-5", currentModel?.model)
        assertEquals("high", currentModel?.effort)
        assertEquals("implement", outcome.currentStepId)
    }

    @Test
    fun `unusable current model degrades to null while the outcome maps normally`() {
        val unusable = mapOf(
            "absent" to null,
            "non-object string" to """"current_model": "opus-5"""",
            "non-object array" to """"current_model": ["opus-5"]""",
            "blank model" to """"current_model": {"model": "   "}""",
            "non-string model" to """"current_model": {"model": ["opus-5"]}""",
            "numeric model" to """"current_model": {"model": 5}""",
            "boolean model" to """"current_model": {"model": true}""",
            "missing model" to """"current_model": {"effort": "high"}""",
        )
        for ((case, block) in unusable) {
            val outcome = IdeStatusJsonMapper.map(goalPayload(planning = null, currentModel = block), now, 0)
            assertTrue("$case must still map to Active", outcome is SkillBillStatusOutcome.Active)
            outcome as SkillBillStatusOutcome.Active
            assertNull("$case must degrade the model to null", outcome.currentModel)
            assertEquals("$case must keep the surrounding outcome", "implement", outcome.currentStepId)
        }
    }

    @Test
    fun `unusable effort degrades to a null effort while the model survives`() {
        val unusable = mapOf(
            "blank effort" to """"current_model": {"model": "opus-5", "effort": "  "}""",
            "non-string effort" to """"current_model": {"model": "opus-5", "effort": {"level": "high"}}""",
            "numeric effort" to """"current_model": {"model": "opus-5", "effort": 3}""",
            "boolean effort" to """"current_model": {"model": "opus-5", "effort": false}""",
        )
        for ((case, block) in unusable) {
            val outcome = IdeStatusJsonMapper.map(goalPayload(planning = null, currentModel = block), now, 0)
            assertTrue("$case must still map to Active", outcome is SkillBillStatusOutcome.Active)
            val currentModel = (outcome as SkillBillStatusOutcome.Active).currentModel
            assertEquals("$case must keep the model", "opus-5", currentModel?.model)
            assertNull("$case must degrade the effort to null", currentModel?.effort)
        }
    }

    @Test
    fun `stale payload carrying a current model maps it onto the stale outcome`() {
        val json = goalPayload(planning = null, currentModel = """"current_model": {"model": "opus-5"}""")
            .replace("\"freshness\":\"fresh\"", "\"freshness\":\"stale\"")
        val outcome = IdeStatusJsonMapper.map(json, now, 0)
        assertTrue(outcome is SkillBillStatusOutcome.Stale)
        assertEquals("opus-5", (outcome as SkillBillStatusOutcome.Stale).currentModel?.model)
    }

    @Test
    fun `malformed planning degrades to null without failing the mapping`() {
        val malformed = mapOf(
            "non-object" to "\"planning\": \"nope\"",
            "missing required key" to """"planning": {"state": "preplanned", "planned_subtask_count": 1, "total_subtask_count": 4}""",
            "wrong type" to """"planning": {"state": "preplanned", "shared_preplan_prepared": "yes", "planned_subtask_count": 1, "total_subtask_count": 4}""",
            "negative count" to """"planning": {"state": "preplanned", "shared_preplan_prepared": false, "planned_subtask_count": -1, "total_subtask_count": 4}""",
        )
        for ((case, block) in malformed) {
            val outcome = IdeStatusJsonMapper.map(goalPayload(planning = block), now, 0)
            assertTrue("$case must still map to Active", outcome is SkillBillStatusOutcome.Active)
            assertNull("$case must degrade planning to null", (outcome as SkillBillStatusOutcome.Active).planning)
        }
    }

    @Test
    fun `fresh paused maps to Paused and stale paused still maps to Stale`() {
        val fresh = IdeStatusJsonMapper.map(runtimeFixture(lifecycle = "paused"), now, 0)
        assertTrue(fresh is SkillBillStatusOutcome.Paused)
        assertEquals("implement", (fresh as SkillBillStatusOutcome.Paused).currentStepId)

        val stale = IdeStatusJsonMapper.map(
            runtimeFixture(lifecycle = "paused", freshness = "stale"),
            now,
            0,
        )
        assertTrue(stale is SkillBillStatusOutcome.Stale)
    }

    @Test
    fun `active payload mapping is unchanged by the paused split`() {
        val outcome = IdeStatusJsonMapper.map(fixture("active-runtime.json"), now, 0)
        assertTrue(outcome is SkillBillStatusOutcome.Active)
        outcome as SkillBillStatusOutcome.Active
        assertEquals("SKILL-148", outcome.issueKey)
        assertEquals("implement", outcome.currentStepId)
        assertNull(outcome.planning)
    }

    @Test
    fun `expected contract version constant matches fixture`() {
        assertTrue(fixture("active-runtime.json").contains("\"contract_version\": \"$IDE_STATUS_CONTRACT_VERSION\""))
    }

    /**
     * Rewrites the named fields only. A blanket string replace would also rewrite any
     * other occurrence of the same value (workflow family, summary text, step id), so
     * the test would silently exercise a payload it does not describe.
     */
    private fun runtimeFixture(lifecycle: String? = null, freshness: String? = null): String {
        var json = fixture("active-runtime.json")
        lifecycle?.let { json = json.replaceField("lifecycle_state", "active", it) }
        freshness?.let { json = json.replaceField("freshness", "fresh", it) }
        return json
    }

    @Test
    fun `pause signals parse when present and stay absent when omitted`() {
        val withSignals = goalPayload(null).dropLast(1) +
            ",\"pause_requested\":true,\"paused_at\":\"2026-08-06T09:30:00Z\"}"
        val parsed = IdeStatusJsonMapper.map(withSignals, now, 0) as SkillBillStatusOutcome.Active
        assertEquals(true, parsed.pauseRequested)
        assertEquals(java.time.Instant.parse("2026-08-06T09:30:00Z"), parsed.pausedAt)

        // Omitting both keys must stay absent — not coerced to false — and must not
        // turn a valid payload into a malformed outcome.
        val without = IdeStatusJsonMapper.map(goalPayload(null), now, 0) as SkillBillStatusOutcome.Active
        assertNull(without.pauseRequested)
        assertNull(without.pausedAt)
    }

    @Test
    fun `active duration parses when present and stays absent when omitted`() {
        val withDuration = goalPayload(null).dropLast(1) +
            ",\"active_duration_ms\":1380000,\"active_duration_as_of\":\"2026-08-06T09:59:00Z\"}"
        val parsed = IdeStatusJsonMapper.map(withDuration, now, 0) as SkillBillStatusOutcome.Active
        assertEquals(1_380_000L, parsed.activeDurationMs)
        assertEquals(java.time.Instant.parse("2026-08-06T09:59:00Z"), parsed.activeDurationAsOf)

        // Absent must stay null rather than becoming zero: null falls back to the wall clock,
        // whereas zero would render "0s" as a measurement.
        val without = IdeStatusJsonMapper.map(goalPayload(null), now, 0) as SkillBillStatusOutcome.Active
        assertNull(without.activeDurationMs)
        assertNull(without.activeDurationAsOf)
    }

    @Test
    fun `a settled lifecycle keeps the accumulated active duration`() {
        val terminal = runtimeFixture(lifecycle = "terminal").trimEnd().dropLast(1) +
            ",\"active_duration_ms\":8667046}"
        val done = IdeStatusJsonMapper.map(terminal, now, 0) as SkillBillStatusOutcome.Done
        assertEquals(8_667_046L, done.activeDurationMs)
        assertNull("A released lease publishes no anchor", done.activeDurationAsOf)
    }

    @Test
    fun `a malformed active duration is dropped rather than rendered as work`() {
        val cases = listOf(
            "\"active_duration_ms\":-1",
            "\"active_duration_ms\":\"1380000\"",
            "\"active_duration_ms\":null",
        )
        cases.forEach { field ->
            val payload = goalPayload(null).dropLast(1) + ",$field}"
            val parsed = IdeStatusJsonMapper.map(payload, now, 0) as SkillBillStatusOutcome.Active
            assertNull("Expected $field to be dropped", parsed.activeDurationMs)
        }

        val unparseableAnchor = goalPayload(null).dropLast(1) +
            ",\"active_duration_ms\":1000,\"active_duration_as_of\":\"not-an-instant\"}"
        val parsed = IdeStatusJsonMapper.map(unparseableAnchor, now, 0) as SkillBillStatusOutcome.Active
        assertEquals(1_000L, parsed.activeDurationMs)
        assertNull("An unparseable anchor must not license a live tail", parsed.activeDurationAsOf)
    }

    private fun String.replaceField(field: String, from: String, to: String): String {
        val original = "\"$field\": \"$from\""
        check(contains(original)) { "Fixture no longer contains $original" }
        return replace(original, "\"$field\": \"$to\"")
    }

    private fun goalPayload(planning: String?, currentModel: String? = null): String =
        buildString {
            append("{\"contract_version\":\"$IDE_STATUS_CONTRACT_VERSION\",")
            append("\"repository_identity\":\"repo-root-realpath-v1:/repo\",")
            append("\"lifecycle_state\":\"active\",\"freshness\":\"fresh\",")
            append("\"issue_key\":\"SKILL-165\",\"workflow_id\":\"wfl-1\",")
            append("\"workflow_family\":\"feature-goal\",")
            append("\"current_step\":{\"id\":\"implement\",\"label\":\"Implement\"},")
            append("\"progress\":{\"completed\":1,\"total\":4},")
            append("\"started_at\":\"2026-08-06T08:00:00Z\",")
            append("\"updated_at\":\"2026-08-06T09:59:00Z\",")
            append("\"summary\":\"Goal SKILL-165 in progress.\"")
            planning?.let { append(',').append(it) }
            currentModel?.let { append(',').append(it) }
            append('}')
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
    private val destroyed = CountDownLatch(1)
    private val hangDeadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(hangMs)

    init {
        onStart()
    }

    override val inputStream: InputStream = ByteArrayInputStream(ByteArray(0))
    override val errorStream: InputStream = ByteArrayInputStream(stderr.toByteArray())
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        val ms = unit.toMillis(timeout).coerceAtLeast(0L)
        if (!alive) return true
        destroyed.await(ms, TimeUnit.MILLISECONDS)
        return !alive || System.nanoTime() >= hangDeadlineNs
    }
    override fun destroyForcibly() {
        alive = false
        destroyed.countDown()
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

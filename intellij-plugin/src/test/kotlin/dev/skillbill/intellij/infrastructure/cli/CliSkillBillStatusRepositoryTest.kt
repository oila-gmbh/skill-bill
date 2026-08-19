package dev.skillbill.intellij.infrastructure.cli

import dev.skillbill.intellij.domain.DEFAULT_CLI_TIMEOUT_MS
import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import dev.skillbill.intellij.domain.StatusClock
import dev.skillbill.intellij.domain.UnavailableReason
import dev.skillbill.intellij.fakes.FakePreferenceCache
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class CliSkillBillStatusRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the poll timeout keeps headroom over an observed nine-second work status`() {
        assertTrue(
            "poll timeout $DEFAULT_CLI_TIMEOUT_MS must stay at or above 30000ms",
            DEFAULT_CLI_TIMEOUT_MS >= 30_000L,
        )
    }

    @Test
    fun `missing executable is typed unavailable`() = runBlocking {
        val prefs = FakePreferenceCache(cliOverride = null)
        val repo = CliSkillBillStatusRepository(
            preferences = prefs,
            processRunner = ProcessRunner(),
            clock = StatusClock.fixed(Instant.parse("2026-08-06T10:00:00Z")),
            executableResolver = { CliExecutableResolution.Missing },
        )
        val outcome = repo.fetchStatus(temp.root.toPath())
        assertTrue(outcome is SkillBillStatusOutcome.Unavailable)
        assertEquals(
            UnavailableReason.MISSING_EXECUTABLE,
            (outcome as SkillBillStatusOutcome.Unavailable).reasonCode,
        )
    }

    @Test
    fun `misconfigured executable override is typed unavailable`() = runBlocking {
        val prefs = FakePreferenceCache(cliOverride = "/no/such/skill-bill")
        val repo = CliSkillBillStatusRepository(
            preferences = prefs,
            processRunner = ProcessRunner(),
            clock = StatusClock.fixed(Instant.parse("2026-08-06T10:00:00Z")),
            executableResolver = { CliExecutableResolution.Misconfigured },
        )
        val outcome = repo.fetchStatus(temp.root.toPath())
        assertTrue(outcome is SkillBillStatusOutcome.Unavailable)
        assertEquals(
            UnavailableReason.MISCONFIGURED,
            (outcome as SkillBillStatusOutcome.Unavailable).reasonCode,
        )
    }

    @Test
    fun `process start failure is typed without leaking paths`() = runBlocking {
        val prefs = FakePreferenceCache()
        val runner = ProcessRunner(
            processFactory = ProcessFactory { _, _ ->
                error("boom /home/user/secret")
            },
        )
        val repo = CliSkillBillStatusRepository(
            preferences = prefs,
            processRunner = runner,
            clock = StatusClock.fixed(Instant.parse("2026-08-06T10:00:00Z")),
            executableResolver = { CliExecutableResolution.Found("/usr/bin/skill-bill") },
        )
        val root = temp.newFolder("repo").toPath()
        Files.createDirectories(root)
        val outcome = repo.fetchStatus(root)
        assertTrue(outcome is SkillBillStatusOutcome.Unavailable)
        assertEquals(
            UnavailableReason.PROCESS_FAILURE,
            (outcome as SkillBillStatusOutcome.Unavailable).reasonCode,
        )
        assertTrue(!outcome.toString().contains("/home/user"))
    }

    @Test
    fun `successful process output maps through repository`() = runBlocking {
        val json = javaClass.classLoader.getResourceAsStream("fixtures/active-runtime.json")!!
            .readBytes()
            .toString(Charsets.UTF_8)
        val prefs = FakePreferenceCache()
        val runner = ProcessRunner(
            processFactory = ProcessFactory { _, _ ->
                object : ProcessHandle {
                    override val inputStream = json.byteInputStream()
                    override val errorStream = ByteArray(0).inputStream()
                    override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
                    override fun destroyForcibly() {}
                    override fun exitValue() = 0
                    override fun isAlive() = false
                }
            },
        )
        val repo = CliSkillBillStatusRepository(
            preferences = prefs,
            processRunner = runner,
            clock = StatusClock.fixed(Instant.parse("2026-08-06T10:00:00Z")),
            executableResolver = { CliExecutableResolution.Found("/usr/bin/skill-bill") },
        )
        // Need a real path for toRealPath()
        val root = temp.newFolder("repo").toPath()
        Files.createDirectories(root)
        val outcome = repo.fetchStatus(root)
        assertTrue(outcome is SkillBillStatusOutcome.Active)
    }
}

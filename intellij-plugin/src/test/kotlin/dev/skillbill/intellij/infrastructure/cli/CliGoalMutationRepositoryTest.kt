package dev.skillbill.intellij.infrastructure.cli

import dev.skillbill.intellij.application.GoalPauseOutcome
import dev.skillbill.intellij.application.GoalStopOutcome
import dev.skillbill.intellij.fakes.FakePreferenceCache
import dev.skillbill.intellij.fakes.ScriptedProcessFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exact-argv and failure-summary coverage for both mutating verbs. Summaries must never
 * carry stdout, stderr, exception text, or a filesystem path.
 */
class CliGoalMutationRepositoryTest {
    private val secretStdout = "SECRET_STDOUT_MARKER"
    private val prefs = FakePreferenceCache()

    @Test
    fun `pause and stop send the exact argv with a canonical repo root`() {
        val root = Files.createTempDirectory("goal-mutation")
        val canonical = root.toAbsolutePath().normalize().toRealPath().toString()

        val pauseFactory = ScriptedProcessFactory(exitCode = 0)
        val pause = CliGoalPauseRepository(
            preferences = prefs,
            processRunner = ProcessRunner(processFactory = pauseFactory),
            executableResolver = { CliExecutableResolution.Found("/usr/bin/skill-bill") },
        )
        val pauseOutcome = runBlocking { pause.requestPause(root.resolve("..").resolve(root.fileName), "SKILL-168") }
        assertEquals(GoalPauseOutcome.Requested, pauseOutcome)
        assertEquals(
            listOf("/usr/bin/skill-bill", "goal", "pause", "SKILL-168", "--repo-root", canonical),
            pauseFactory.commands.single(),
        )

        val stopFactory = ScriptedProcessFactory(exitCode = 0)
        val stop = CliGoalStopRepository(
            preferences = prefs,
            processRunner = ProcessRunner(processFactory = stopFactory),
            executableResolver = { CliExecutableResolution.Found("/usr/bin/skill-bill") },
        )
        val stopOutcome = runBlocking { stop.requestStop(root, "SKILL-168") }
        assertEquals(GoalStopOutcome.Requested, stopOutcome)
        assertEquals(
            listOf("/usr/bin/skill-bill", "goal", "stop", "SKILL-168", "--repo-root", canonical),
            stopFactory.commands.single(),
        )
    }

    @Test
    fun `a blank or whitespace issue key never starts a process`() {
        for (key in listOf("", "   ", "\t")) {
            val pauseFactory = ScriptedProcessFactory()
            val pause = CliGoalPauseRepository(
                preferences = prefs,
                processRunner = ProcessRunner(processFactory = pauseFactory),
                executableResolver = { CliExecutableResolution.Found("/usr/bin/skill-bill") },
            )
            val stopFactory = ScriptedProcessFactory()
            val stop = CliGoalStopRepository(
                preferences = prefs,
                processRunner = ProcessRunner(processFactory = stopFactory),
                executableResolver = { CliExecutableResolution.Found("/usr/bin/skill-bill") },
            )
            val root = Files.createTempDirectory("blank-key")
            runBlocking {
                assertTrue(pause.requestPause(root, key) is GoalPauseOutcome.Failed)
                assertTrue(stop.requestStop(root, key) is GoalStopOutcome.Failed)
            }
            assertTrue("blank key must not spawn a process", pauseFactory.commands.isEmpty())
            assertTrue("blank key must not spawn a process", stopFactory.commands.isEmpty())
        }
    }

    @Test
    fun `every failure summary is bounded and leaks no output or path`() {
        val root = Files.createTempDirectory("leak-check")
        val canonical = root.toAbsolutePath().normalize().toRealPath().toString()
        val summaries = mutableListOf<String>()

        summaries += failureSummaries(root) { ProcessRunner(processFactory = ScriptedProcessFactory(exitCode = 3, stdout = secretStdout)) }
        summaries += failureSummaries(root, resolution = CliExecutableResolution.Missing) {
            ProcessRunner(processFactory = ScriptedProcessFactory())
        }
        summaries += failureSummaries(root, resolution = CliExecutableResolution.Misconfigured) {
            ProcessRunner(processFactory = ScriptedProcessFactory())
        }
        // Thrown-from-runner path.
        summaries += failureSummaries(root) {
            ProcessRunner(
                processFactory = { _, _ -> throw IllegalStateException("BOOM_$secretStdout at $canonical") },
            )
        }
        // Cancelled runner.
        summaries += failureSummaries(root) {
            ProcessRunner(processFactory = ScriptedProcessFactory()).apply { cancelAll() }
        }
        // Timed out: the held process never finishes inside the timeout.
        summaries += failureSummaries(root, timeoutMs = 50) {
            ProcessRunner(processFactory = ScriptedProcessFactory(hold = true))
        }
        // Unusable project root.
        summaries += failureSummaries(Path.of("/definitely/not/a/real/root/for/skill-bill")) {
            ProcessRunner(processFactory = ScriptedProcessFactory())
        }

        assertTrue("expected every failure path to be covered", summaries.size >= 14)
        for (summary in summaries) {
            assertTrue("summary must be non-empty", summary.isNotBlank())
            assertTrue("summary must stay bounded: $summary", summary.length <= 120)
            assertTrue("summary leaked stdout: $summary", !summary.contains(secretStdout))
            assertTrue("summary leaked an exception: $summary", !summary.contains("BOOM"))
            assertTrue("summary leaked a path: $summary", !summary.contains(canonical))
            assertTrue("summary leaked a path: $summary", !summary.contains("/"))
        }
    }

    private fun failureSummaries(
        root: Path,
        resolution: CliExecutableResolution = CliExecutableResolution.Found("/usr/bin/skill-bill"),
        timeoutMs: Long = 2_000,
        runner: () -> ProcessRunner,
    ): List<String> = runBlocking {
        val pause = CliGoalPauseRepository(prefs, runner(), { resolution }, timeoutMs)
            .requestPause(root, "SKILL-168")
        val stop = CliGoalStopRepository(prefs, runner(), { resolution }, timeoutMs)
            .requestStop(root, "SKILL-168")
        listOfNotNull(
            (pause as? GoalPauseOutcome.Failed)?.summary,
            (stop as? GoalStopOutcome.Failed)?.summary,
        )
    }
}

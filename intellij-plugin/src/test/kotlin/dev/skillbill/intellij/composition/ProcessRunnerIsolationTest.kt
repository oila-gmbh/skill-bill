package dev.skillbill.intellij.composition

import dev.skillbill.intellij.application.GoalStopOutcome
import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import dev.skillbill.intellij.fakes.FakePreferenceCache
import dev.skillbill.intellij.fakes.FakeStatusRepository
import dev.skillbill.intellij.fakes.ScriptedProcessFactory
import dev.skillbill.intellij.infrastructure.cli.CliExecutableResolution
import dev.skillbill.intellij.infrastructure.cli.CliExecutableSource
import dev.skillbill.intellij.infrastructure.cli.CliGoalStopRepository
import dev.skillbill.intellij.infrastructure.cli.ProcessRunner
import dev.skillbill.intellij.infrastructure.cli.ProcessSpec
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `runCoalesced` coalesces per instance, so a mutating call sharing the poll runner would
 * join an in-flight poll and return that poll's exit code. Isolation is asserted both
 * structurally (three distinct instances) and behaviourally (a held poll completes nothing).
 */
class ProcessRunnerIsolationTest {
    private val root = Files.createTempDirectory("runner-isolation")

    @Test
    fun `status pause and stop runners are three distinct instances and all are disposed`() {
        val composed = SkillBillStatusCompositionRoot.createForTest(
            projectRoot = root,
            preferences = FakePreferenceCache(),
            statusRepository = FakeStatusRepository { SkillBillStatusOutcome.Idle(Instant.now(), "idle") },
        )
        assertNotSame(composed.processRunner, composed.pauseProcessRunner)
        assertNotSame(composed.processRunner, composed.stopProcessRunner)
        assertNotSame(composed.pauseProcessRunner, composed.stopProcessRunner)
        assertEquals(
            "three distinct runners",
            3,
            setOf(
                System.identityHashCode(composed.processRunner),
                System.identityHashCode(composed.pauseProcessRunner),
                System.identityHashCode(composed.stopProcessRunner),
            ).size,
        )

        composed.dispose()
        // A cancelled runner short-circuits instead of spawning; proves all three were cancelled.
        runBlocking {
            for (runner in listOf(composed.processRunner, composed.pauseProcessRunner, composed.stopProcessRunner)) {
                val result = withContext(Dispatchers.Default) {
                    runner.runCoalesced(
                        ProcessSpec(listOf("skill-bill"), timeoutMs = 500, stdoutLimitBytes = 16, stderrLimitBytes = 16),
                    )
                }
                assertTrue("dispose must cancel every runner", result.cancelled)
            }
        }
    }

    @Test
    fun `a held status poll never completes a concurrent stop`() = runBlocking {
        val pollFactory = ScriptedProcessFactory(exitCode = 77, stdout = "poll", hold = true)
        val stopFactory = ScriptedProcessFactory(exitCode = 0, stdout = "stopped")
        val pollRunner = ProcessRunner(processFactory = pollFactory)
        val stopRunner = ProcessRunner(processFactory = stopFactory)

        val poll = async(Dispatchers.Default) {
            pollRunner.runCoalesced(
                ProcessSpec(listOf("skill-bill", "work", "status"), timeoutMs = 5_000, stdoutLimitBytes = 1024, stderrLimitBytes = 1024),
            )
        }
        // Let the poll get in flight before the mutation starts.
        delay(100)

        val stop = CliGoalStopRepository(
            preferences = FakePreferenceCache(),
            processRunner = stopRunner,
            executableResolver = { CliExecutableResolution.Found("/usr/bin/skill-bill", CliExecutableSource.SEARCH_PATH) },
        )
        val outcome = withContext(Dispatchers.Default) { stop.requestStop(root, "SKILL-168") }

        // The stop returned its own exit code (0 → Requested), not the poll's 77.
        assertEquals(GoalStopOutcome.Requested, outcome)
        assertEquals("the stop started its own process", 1, stopFactory.commands.size)
        assertTrue("the poll was still in flight", !poll.isCompleted)

        pollFactory.release()
        assertEquals(77, poll.await().exitCode)
    }
}

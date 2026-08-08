package dev.skillbill.intellij.fakes

import dev.skillbill.intellij.application.GoalPauseOutcome
import dev.skillbill.intellij.application.GoalPauseRepository
import dev.skillbill.intellij.application.GoalStopOutcome
import dev.skillbill.intellij.application.GoalStopRepository
import dev.skillbill.intellij.application.PreferenceCachePort
import dev.skillbill.intellij.application.StatusRepository
import dev.skillbill.intellij.domain.DEFAULT_REFRESH_INTERVAL_SECONDS
import dev.skillbill.intellij.domain.FEATURE_GOAL_WORKFLOW_FAMILY
import dev.skillbill.intellij.domain.LastKnownDisplayCache
import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import dev.skillbill.intellij.infrastructure.cli.ProcessFactory
import dev.skillbill.intellij.infrastructure.cli.ProcessHandle
import dev.skillbill.intellij.presentation.SkillBillStatusUiState
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

class FakePreferenceCache(
    private var cliOverride: String? = null,
    private var refreshIntervalSeconds: Long = DEFAULT_REFRESH_INTERVAL_SECONDS,
    private var cache: LastKnownDisplayCache? = null,
) : PreferenceCachePort {
    val rejectedWrites = CopyOnWriteArrayList<String>()

    /** Counts every attempted display-cache write, accepted or rejected. */
    val cacheWriteAttempts = AtomicInteger(0)

    override fun getCliExecutableOverride(): String? = cliOverride

    override fun setCliExecutableOverride(path: String?) {
        if (path != null && looksForbidden(path)) {
            rejectedWrites += path
            return
        }
        cliOverride = path
    }

    override fun getRefreshIntervalSeconds(): Long = refreshIntervalSeconds

    override fun setRefreshIntervalSeconds(seconds: Long) {
        refreshIntervalSeconds = seconds.coerceIn(1L, 3_600L)
    }

    override fun getLastKnownDisplayCache(): LastKnownDisplayCache? = cache

    override fun setLastKnownDisplayCache(cache: LastKnownDisplayCache?) {
        cacheWriteAttempts.incrementAndGet()
        if (cache != null && looksForbidden(cache.display.summary)) {
            rejectedWrites += cache.display.summary
            return
        }
        this.cache = cache
    }

    private fun looksForbidden(value: String): Boolean {
        val lower = value.lowercase()
        return lower.contains("token=") ||
            lower.contains("bearer ") ||
            lower.startsWith("sk-") ||
            lower.contains("stderr") ||
            value.length > 2_048
    }
}

class FakeStatusRepository(
    private val handler: suspend (Path) -> SkillBillStatusOutcome,
) : StatusRepository {
    val callCount = AtomicInteger(0)
    val inFlight = AtomicInteger(0)
    val maxInFlight = AtomicInteger(0)

    override suspend fun fetchStatus(projectRoot: Path): SkillBillStatusOutcome {
        callCount.incrementAndGet()
        val flying = inFlight.incrementAndGet()
        maxInFlight.updateAndGet { maxOf(it, flying) }
        return try {
            handler(projectRoot)
        } finally {
            inFlight.decrementAndGet()
        }
    }
}

class ControllableClock(initial: java.time.Instant) : dev.skillbill.intellij.domain.StatusClock {
    @Volatile
    private var now: java.time.Instant = initial

    override fun now(): java.time.Instant = now

    fun set(instant: java.time.Instant) {
        now = instant
    }

    fun advanceSeconds(seconds: Long) {
        now = now.plusSeconds(seconds)
    }
}

suspend fun awaitCallCount(repo: FakeStatusRepository, atLeast: Int, timeoutMs: Long = 2_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (repo.callCount.get() < atLeast && System.currentTimeMillis() < deadline) {
        delay(20)
    }
}

fun gate(): CompletableDeferred<Unit> = CompletableDeferred()

class FakeGoalPauseRepository(
    private val outcome: GoalPauseOutcome = GoalPauseOutcome.Requested,
) : GoalPauseRepository {
    val invocations = CopyOnWriteArrayList<MutationInvocation>()

    override suspend fun requestPause(projectRoot: Path, issueKey: String): GoalPauseOutcome {
        invocations += MutationInvocation(projectRoot, issueKey, Thread.currentThread().name)
        return outcome
    }
}

class FakeGoalStopRepository(
    private val outcome: GoalStopOutcome = GoalStopOutcome.Requested,
) : GoalStopRepository {
    val invocations = CopyOnWriteArrayList<MutationInvocation>()

    override suspend fun requestStop(projectRoot: Path, issueKey: String): GoalStopOutcome {
        invocations += MutationInvocation(projectRoot, issueKey, Thread.currentThread().name)
        return outcome
    }
}

/** [threadName] is recorded so tests can prove dispatch happened off the EDT. */
data class MutationInvocation(
    val projectRoot: Path,
    val issueKey: String,
    val threadName: String,
)

/**
 * Scripted [ProcessFactory] that never spawns a real process. A held process models an
 * in-flight poll: it stays alive until [release] so a concurrent mutation can be shown
 * to start its own process rather than joining the poll.
 */
class ScriptedProcessFactory(
    private val exitCode: Int = 0,
    private val stdout: String = "",
    private val hold: Boolean = false,
) : ProcessFactory {
    val commands = CopyOnWriteArrayList<List<String>>()
    private val released = java.util.concurrent.CountDownLatch(if (hold) 1 else 0)

    fun release() {
        while (released.count > 0) released.countDown()
    }

    override fun start(command: List<String>, workingDirectory: String?): ProcessHandle {
        commands += command
        return object : ProcessHandle {
            override val inputStream: InputStream = ByteArrayInputStream(stdout.toByteArray())
            override val errorStream: InputStream = ByteArrayInputStream(ByteArray(0))
            private var finished = !hold

            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
                if (finished) return true
                finished = released.await(timeout, unit)
                return finished
            }

            override fun destroyForcibly() {
                finished = true
            }

            override fun exitValue(): Int = exitCode

            override fun isAlive(): Boolean = !finished
        }
    }
}

/** Active UI state carrying the goal-control inputs, so tests do not restate every field. */
fun activeUiState(
    issueKey: String? = "SKILL-168",
    workflowFamily: String? = FEATURE_GOAL_WORKFLOW_FAMILY,
    pauseRequested: Boolean? = null,
): SkillBillStatusUiState.Active =
    SkillBillStatusUiState.Active(
        headline = "Skill Bill: goal",
        detail = "working",
        goalElapsed = null,
        subtaskElapsed = null,
        progressCompleted = 1,
        progressTotal = 3,
        issueKey = issueKey,
        workflowId = "w1",
        stepLabel = "Implement",
        startedAt = null,
        subtaskStartedAt = null,
        lastUpdated = null,
        workflowFamily = workflowFamily,
        pauseRequested = pauseRequested,
    )

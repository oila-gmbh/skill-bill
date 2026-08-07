package dev.skillbill.intellij.fakes

import dev.skillbill.intellij.application.PreferenceCachePort
import dev.skillbill.intellij.application.StatusRepository
import dev.skillbill.intellij.domain.DEFAULT_REFRESH_INTERVAL_SECONDS
import dev.skillbill.intellij.domain.LastKnownDisplayCache
import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
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

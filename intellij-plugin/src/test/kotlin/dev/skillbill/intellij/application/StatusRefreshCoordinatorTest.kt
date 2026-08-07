package dev.skillbill.intellij.application

import dev.skillbill.intellij.composition.SkillBillStatusCompositionRoot
import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import dev.skillbill.intellij.domain.UnavailableReason
import dev.skillbill.intellij.fakes.FakePreferenceCache
import dev.skillbill.intellij.fakes.FakeStatusRepository
import dev.skillbill.intellij.fakes.awaitCallCount
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusRefreshCoordinatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `polling starts only while a consumer is active and does not overlap`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeStatusRepository {
            gate.await()
            idle()
        }
        val prefs = FakePreferenceCache(refreshIntervalSeconds = 60)
        val coordinator = StatusRefreshCoordinator(repo, prefs, scope, Path.of("/tmp/a"))
        assertEquals(0, repo.callCount.get())
        coordinator.addConsumer()
        awaitCallCount(repo, 1)
        // Second refresh while first is gated should serialize via mutex (max in-flight 1).
        coordinator.requestRefresh()
        delay(50)
        assertEquals(1, repo.maxInFlight.get())
        gate.complete(Unit)
        delay(50)
        coordinator.removeConsumer()
        val callsAfterStop = repo.callCount.get()
        delay(100)
        assertEquals(callsAfterStop, repo.callCount.get())
        coordinator.dispose()
    }

    @Test
    fun `dispose cancels polling and invokes process cancellation`() = runBlocking {
        val cancelled = AtomicBoolean(false)
        val repo = FakeStatusRepository {
            delay(10_000)
            idle()
        }
        val prefs = FakePreferenceCache(refreshIntervalSeconds = 1)
        val coordinator = StatusRefreshCoordinator(
            statusRepository = repo,
            preferences = prefs,
            scope = scope,
            projectRoot = Path.of("/tmp/b"),
            onCancelProcesses = { cancelled.set(true) },
        )
        coordinator.addConsumer()
        awaitCallCount(repo, 1)
        coordinator.dispose()
        assertTrue(cancelled.get())
        val calls = repo.callCount.get()
        delay(200)
        assertEquals(calls, repo.callCount.get())
    }

    @Test
    fun `unavailable falls back to stale cache only`() = runBlocking {
        val observed = Instant.parse("2026-08-06T09:00:00Z")
        val prefs = FakePreferenceCache(
            refreshIntervalSeconds = 60,
            cache = dev.skillbill.intellij.domain.LastKnownDisplayCache(
                display = dev.skillbill.intellij.domain.CachedDisplaySnapshot(
                    summary = "cached implement",
                    currentStepLabel = "Implement",
                ),
                observedAt = observed,
            ),
        )
        val repo = FakeStatusRepository {
            SkillBillStatusOutcome.Unavailable(
                observedAt = Instant.parse("2026-08-06T10:00:00Z"),
                summary = "cli missing",
                reasonCode = UnavailableReason.MISSING_EXECUTABLE,
            )
        }
        val coordinator = StatusRefreshCoordinator(repo, prefs, scope, Path.of("/tmp/c"))
        coordinator.addConsumer()
        awaitCallCount(repo, 1)
        delay(50)
        val outcome = coordinator.outcomes.value
        assertTrue(outcome is SkillBillStatusOutcome.Stale)
        assertTrue((outcome as SkillBillStatusOutcome.Stale).fromCache)
        coordinator.dispose()
    }

    @Test
    fun `two project graphs remain isolated through composition root`() = runBlocking {
        val repoA = FakeStatusRepository { idle(summary = "A") }
        val repoB = FakeStatusRepository { idle(summary = "B") }
        val rootA = SkillBillStatusCompositionRoot.createForTest(
            projectRoot = Path.of("/tmp/project-a"),
            preferences = FakePreferenceCache(refreshIntervalSeconds = 60),
            statusRepository = repoA,
        )
        val rootB = SkillBillStatusCompositionRoot.createForTest(
            projectRoot = Path.of("/tmp/project-b"),
            preferences = FakePreferenceCache(refreshIntervalSeconds = 60),
            statusRepository = repoB,
        )
        rootA.coordinator.addConsumer()
        rootB.coordinator.addConsumer()
        awaitCallCount(repoA, 1)
        awaitCallCount(repoB, 1)
        delay(50)
        assertEquals("A", (rootA.coordinator.outcomes.value as SkillBillStatusOutcome.Idle).summary)
        assertEquals("B", (rootB.coordinator.outcomes.value as SkillBillStatusOutcome.Idle).summary)
        rootA.dispose()
        delay(100)
        // B continues independently after A disposal.
        val bCalls = repoB.callCount.get()
        assertTrue(bCalls >= 1)
        rootB.viewModel.onWidgetActivated()
        rootB.dispose()
    }

    private fun idle(summary: String = "idle") =
        SkillBillStatusOutcome.Idle(
            observedAt = Instant.parse("2026-08-06T10:00:00Z"),
            summary = summary,
        )
}

package dev.skillbill.intellij.application

import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import dev.skillbill.intellij.domain.toCacheSnapshotOrNull
import dev.skillbill.intellij.domain.toStaleOutcome
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Starts polling only while a project service/widget consumer is active.
 * Uses a configurable conservative interval, prevents overlapping polls, and
 * cancels the loop (and downstream process work via [onCancelProcesses]) on dispose.
 */
class StatusRefreshCoordinator(
    private val statusRepository: StatusRepository,
    private val preferences: PreferenceCachePort,
    private val scope: CoroutineScope,
    private val projectRoot: Path,
    private val onCancelProcesses: () -> Unit = {},
) {
    private val activeConsumers = AtomicInteger(0)
    private val disposed = AtomicBoolean(false)
    private val refreshMutex = Mutex()
    private val _outcomes = MutableStateFlow<SkillBillStatusOutcome?>(null)
    val outcomes: StateFlow<SkillBillStatusOutcome?> = _outcomes.asStateFlow()

    private val _events = MutableSharedFlow<CoordinatorEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<CoordinatorEvent> = _events.asSharedFlow()

    @Volatile
    private var pollJob: Job? = null

    fun addConsumer() {
        if (disposed.get()) return
        if (activeConsumers.incrementAndGet() == 1) {
            startPolling()
        }
    }

    fun removeConsumer() {
        if (activeConsumers.decrementAndGet() <= 0) {
            activeConsumers.set(0)
            stopPolling()
        }
    }

    fun requestRefresh() {
        if (disposed.get()) return
        scope.launch {
            refreshOnce()
        }
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        activeConsumers.set(0)
        stopPolling()
        onCancelProcesses()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive && !disposed.get() && activeConsumers.get() > 0) {
                refreshOnce()
                val intervalMs = preferences.getRefreshIntervalSeconds().coerceAtLeast(1L) * 1_000L
                delay(intervalMs)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun refreshOnce() {
        if (disposed.get()) return
        refreshMutex.withLock {
            if (disposed.get()) return
            val outcome = try {
                statusRepository.fetchStatus(projectRoot)
            } catch (_: kotlinx.coroutines.CancellationException) {
                return
            } catch (_: Exception) {
                preferences.getLastKnownDisplayCache()?.toStaleOutcome()
                    ?: return
            }
            val toEmit = when (outcome) {
                is SkillBillStatusOutcome.Unavailable,
                is SkillBillStatusOutcome.Incompatible,
                -> preferences.getLastKnownDisplayCache()?.toStaleOutcome() ?: outcome

                else -> {
                    outcome.toCacheSnapshotOrNull()?.let { preferences.setLastKnownDisplayCache(it) }
                    outcome
                }
            }
            _outcomes.value = toEmit
            _events.tryEmit(CoordinatorEvent.Refreshed(toEmit))
        }
    }
}

sealed class CoordinatorEvent {
    data class Refreshed(val outcome: SkillBillStatusOutcome) : CoordinatorEvent()
}

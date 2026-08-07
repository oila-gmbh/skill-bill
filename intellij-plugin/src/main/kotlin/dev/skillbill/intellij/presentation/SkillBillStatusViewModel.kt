package dev.skillbill.intellij.presentation

import dev.skillbill.intellij.application.StatusRefreshCoordinator
import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import dev.skillbill.intellij.domain.StatusClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Project-scoped ViewModel. Exposes immutable [StateFlow] of UI state, maps
 * domain outcomes exhaustively, accepts explicit refresh/lifecycle intents, and
 * derives elapsed durations via an injected clock. Contains no process, JSON,
 * filesystem, or IntelliJ status-bar rendering code.
 */
class SkillBillStatusViewModel(
    private val coordinator: StatusRefreshCoordinator,
    private val clock: StatusClock,
    private val scope: CoroutineScope,
    private val tickIntervalMs: Long = 1_000L,
) {
    private val _uiState = MutableStateFlow<SkillBillStatusUiState>(SkillBillStatusUiState.Idle())
    val uiState: StateFlow<SkillBillStatusUiState> = _uiState.asStateFlow()

    @Volatile
    private var latestOutcome: SkillBillStatusOutcome? = null
    private var tickJob: Job? = null
    private var collectJob: Job? = null
    private var started = false

    fun onWidgetActivated() {
        if (!started) {
            started = true
            collectJob = scope.launch {
                coordinator.outcomes.collect { outcome ->
                    if (outcome != null) {
                        latestOutcome = outcome
                        publish(outcome)
                    }
                }
            }
            tickJob = scope.launch {
                while (isActive) {
                    delay(tickIntervalMs)
                    latestOutcome?.let { publish(it) }
                }
            }
        }
        coordinator.addConsumer()
    }

    fun onWidgetDeactivated() {
        coordinator.removeConsumer()
    }

    fun refresh() {
        coordinator.requestRefresh()
    }

    fun dispose() {
        tickJob?.cancel()
        collectJob?.cancel()
        tickJob = null
        collectJob = null
        started = false
    }

    private fun publish(outcome: SkillBillStatusOutcome) {
        _uiState.value = StatusUiMapper.map(outcome, clock.now())
    }
}

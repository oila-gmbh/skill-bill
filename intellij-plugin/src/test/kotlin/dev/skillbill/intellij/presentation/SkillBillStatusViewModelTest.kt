package dev.skillbill.intellij.presentation

import dev.skillbill.intellij.application.StatusRefreshCoordinator
import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import dev.skillbill.intellij.fakes.ControllableClock
import dev.skillbill.intellij.fakes.FakePreferenceCache
import dev.skillbill.intellij.fakes.FakeStatusRepository
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillBillStatusViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `viewModel remaps elapsed on clock ticks without synthesizing absent starts`() = runBlocking {
        val start = Instant.parse("2026-08-06T10:00:00Z")
        val clock = ControllableClock(start.plusSeconds(10))
        val repo = FakeStatusRepository {
            SkillBillStatusOutcome.Active(
                observedAt = clock.now(),
                summary = "active",
                repositoryIdentity = "repo",
                issueKey = "SKILL-148",
                workflowId = "w1",
                workflowFamily = "feature-goal",
                currentStepId = "implement",
                currentStepLabel = "Implement",
                progressCompleted = 1,
                progressTotal = 3,
                startedAt = start,
                currentSubtaskId = "2",
                subtaskStartedAt = null,
                updatedAt = clock.now(),
            )
        }
        val prefs = FakePreferenceCache(refreshIntervalSeconds = 60)
        val coordinator = StatusRefreshCoordinator(repo, prefs, scope, Path.of("/tmp/repo"))
        val vm = SkillBillStatusViewModel(coordinator, clock, scope, tickIntervalMs = 50)
        vm.onWidgetActivated()
        delay(150)
        val first = vm.uiState.value
        assertTrue(first is SkillBillStatusUiState.Active)
        first as SkillBillStatusUiState.Active
        assertEquals(Duration.ofSeconds(10), first.goalElapsed)
        assertNull(first.subtaskElapsed)

        clock.advanceSeconds(20)
        delay(100)
        val second = vm.uiState.value as SkillBillStatusUiState.Active
        assertEquals(Duration.ofSeconds(30), second.goalElapsed)
        assertNull(second.subtaskElapsed)

        vm.dispose()
        coordinator.dispose()
    }
}

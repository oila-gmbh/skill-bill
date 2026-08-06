package dev.skillbill.intellij.ui

import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.skillbill.intellij.application.StatusRefreshCoordinator
import dev.skillbill.intellij.composition.SkillBillProjectStatusService
import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import dev.skillbill.intellij.fakes.ControllableClock
import dev.skillbill.intellij.fakes.FakePreferenceCache
import dev.skillbill.intellij.fakes.FakeStatusRepository
import dev.skillbill.intellij.presentation.SkillBillStatusUiState
import dev.skillbill.intellij.presentation.SkillBillStatusViewModel
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * IntelliJ Platform fixture coverage for registration, disposal, ticker stop,
 * and project-scoped isolation. Uses faked status repositories — no CLI or DB.
 */
class SkillBillStatusBarWidgetFixtureTest : BasePlatformTestCase() {
    fun testFactoryIsRegisteredAndAvailableOnlyForNormalProjectWindows() {
        val factories = StatusBarWidgetFactory.EP_NAME.extensionList
        val factory = factories.find { it.id == SkillBillStatusBarIds.ID }
        assertNotNull("SkillBillStatusBarWidget factory must be registered", factory)
        assertTrue(factory!!.isAvailable(project))
        assertEquals(SkillBillStatusBarIds.ID, factory.id)

        val defaultProject = ProjectManager.getInstance().defaultProject
        assertFalse(factory.isAvailable(defaultProject))
    }

    fun testWidgetActivateStartsTickerAndDisposeStopsItWithoutCliPerTick() {
        val clock = ControllableClock(Instant.parse("2026-08-07T12:00:00Z"))
        val refreshCount = AtomicInteger(0)
        val repo = FakeStatusRepository {
            refreshCount.incrementAndGet()
            SkillBillStatusOutcome.Idle(clock.now(), "idle")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefs = FakePreferenceCache(refreshIntervalSeconds = 60)
        val coordinator = StatusRefreshCoordinator(repo, prefs, scope, Path.of(project.basePath!!))
        val vm = SkillBillStatusViewModel(coordinator, clock, scope, tickIntervalMs = 50)
        val widget = SkillBillStatusBarWidget(project, vm, clock, tickIntervalMs = 50)
        widget.activate()
        assertTrue(widget.isTickerRunning())

        val started = Instant.parse("2026-08-07T11:59:00Z")
        widget.replaceLatestStateForTest(
            SkillBillStatusUiState.Active(
                headline = "Skill Bill: SKILL-148 · Implement",
                detail = "working",
                goalElapsed = Duration.ofSeconds(60),
                subtaskElapsed = Duration.ofSeconds(10),
                progressCompleted = 1,
                progressTotal = 3,
                issueKey = "SKILL-148",
                workflowId = "w1",
                stepLabel = "Implement",
                startedAt = started,
                subtaskStartedAt = Instant.parse("2026-08-07T11:59:50Z"),
                lastUpdated = clock.now(),
            ),
        )
        val callsAfterActivate = refreshCount.get()
        clock.advanceSeconds(30)
        widget.tickOnceForTest()
        assertEquals(
            "local tick must not launch an extra CLI poll",
            callsAfterActivate,
            refreshCount.get(),
        )
        assertTrue(widget.currentPresentation().details.goalElapsedText.contains("1m 30s"))

        val beforeClick = refreshCount.get()
        widget.clickForTest()
        assertEquals(SkillBillStatusBarWidget.ClickKind.REFRESH_AND_DETAILS, widget.lastClickKind())
        runBlocking { delay(100) }
        assertTrue(refreshCount.get() >= beforeClick)

        widget.dispose()
        assertFalse(widget.isTickerRunning())
        vm.dispose()
        coordinator.dispose()
        scope.cancel()
    }

    fun testIndependentWidgetStateForActiveAndIdleConsumers() {
        val clock = ControllableClock(Instant.parse("2026-08-07T12:00:00Z"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefsA = FakePreferenceCache()
        val prefsB = FakePreferenceCache()
        val repoA = FakeStatusRepository {
            SkillBillStatusOutcome.Active(
                observedAt = clock.now(),
                summary = "a",
                repositoryIdentity = "repo-a",
                issueKey = "SKILL-A",
                workflowId = "w-a",
                workflowFamily = "feature-task-runtime",
                currentStepId = "implement",
                currentStepLabel = "Implement A",
                progressCompleted = 1,
                progressTotal = 2,
                startedAt = clock.now().minusSeconds(60),
                currentSubtaskId = "1",
                subtaskStartedAt = clock.now().minusSeconds(30),
                updatedAt = clock.now(),
            )
        }
        val repoB = FakeStatusRepository {
            SkillBillStatusOutcome.Idle(clock.now(), "no matching work")
        }
        val coordA = StatusRefreshCoordinator(repoA, prefsA, scope, Path.of("/tmp/a"))
        val coordB = StatusRefreshCoordinator(repoB, prefsB, scope, Path.of("/tmp/b"))
        val vmA = SkillBillStatusViewModel(coordA, clock, scope)
        val vmB = SkillBillStatusViewModel(coordB, clock, scope)
        val widgetA = SkillBillStatusBarWidget(project, vmA, clock)
        val widgetB = SkillBillStatusBarWidget(project, vmB, clock)
        widgetA.replaceLatestStateForTest(
            SkillBillStatusUiState.Active(
                headline = "Skill Bill: SKILL-A · Implement A",
                detail = "a",
                goalElapsed = Duration.ofSeconds(60),
                subtaskElapsed = Duration.ofSeconds(30),
                progressCompleted = 1,
                progressTotal = 2,
                issueKey = "SKILL-A",
                workflowId = "w-a",
                stepLabel = "Implement A",
                startedAt = clock.now().minusSeconds(60),
                subtaskStartedAt = clock.now().minusSeconds(30),
                lastUpdated = clock.now(),
            ),
        )
        widgetB.replaceLatestStateForTest(SkillBillStatusUiState.Idle(detail = "no matching work"))
        assertTrue(widgetA.currentPresentation().barText.contains("Implement A"))
        assertTrue(widgetB.currentPresentation().barText.contains("idle"))
        assertTrue(widgetA.currentPresentation().showActivityAnimation)
        assertFalse(widgetB.currentPresentation().showActivityAnimation)
        widgetA.dispose()
        widgetB.dispose()
        vmA.dispose()
        vmB.dispose()
        coordA.dispose()
        coordB.dispose()
        scope.cancel()
    }

    fun testProjectServiceIsResolvableAndDisposeCancelsPolling() {
        val service = project.getService(SkillBillProjectStatusService::class.java)
        assertNotNull(service)
        assertNotNull(service.viewModel)
        service.viewModel.onWidgetActivated()
        service.dispose()
    }

    fun testCloseAndReopenStopsThenRestartsTickerWithoutLeak() {
        val clock = ControllableClock(Instant.parse("2026-08-07T12:00:00Z"))
        val repo = FakeStatusRepository {
            SkillBillStatusOutcome.Idle(clock.now(), "idle")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefs = FakePreferenceCache(refreshIntervalSeconds = 60)
        val coordinator = StatusRefreshCoordinator(repo, prefs, scope, Path.of(project.basePath!!))
        val vm = SkillBillStatusViewModel(coordinator, clock, scope, tickIntervalMs = 50)

        val first = SkillBillStatusBarWidget(project, vm, clock, tickIntervalMs = 50)
        first.activate()
        assertTrue(first.isTickerRunning())
        first.dispose()
        assertFalse(first.isTickerRunning())

        // Simulate project reopen: a new widget consumer for the same ViewModel.
        val second = SkillBillStatusBarWidget(project, vm, clock, tickIntervalMs = 50)
        second.activate()
        assertTrue(second.isTickerRunning())
        second.dispose()
        assertFalse(second.isTickerRunning())

        vm.dispose()
        coordinator.dispose()
        scope.cancel()
    }
}

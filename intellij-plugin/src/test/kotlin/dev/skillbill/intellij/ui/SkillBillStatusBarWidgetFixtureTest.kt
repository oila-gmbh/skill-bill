package dev.skillbill.intellij.ui

import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.skillbill.intellij.application.GoalStopOutcome
import dev.skillbill.intellij.application.StatusRefreshCoordinator
import dev.skillbill.intellij.composition.SkillBillProjectStatusService
import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import dev.skillbill.intellij.fakes.ControllableClock
import dev.skillbill.intellij.fakes.FakeGoalPauseRepository
import dev.skillbill.intellij.fakes.FakeGoalStopRepository
import dev.skillbill.intellij.fakes.FakePreferenceCache
import dev.skillbill.intellij.fakes.FakeStatusRepository
import dev.skillbill.intellij.fakes.activeUiState
import dev.skillbill.intellij.presentation.GoalControlKind
import dev.skillbill.intellij.presentation.SkillBillStatusBarPresentation
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
        val started = Instant.parse("2026-08-07T11:59:00Z")
        val subtaskStarted = Instant.parse("2026-08-07T11:59:50Z")
        val refreshCount = AtomicInteger(0)
        // Active from the repository: ViewModel republishes cannot clobber the
        // widget with Idle while we assert local ticker re-anchoring.
        val repo = FakeStatusRepository {
            refreshCount.incrementAndGet()
            SkillBillStatusOutcome.Active(
                observedAt = clock.now(),
                summary = "working",
                repositoryIdentity = "repo",
                issueKey = "SKILL-148",
                workflowId = "w1",
                workflowFamily = "feature-task-runtime",
                currentStepId = "implement",
                currentStepLabel = "Implement",
                progressCompleted = 1,
                progressTotal = 3,
                startedAt = started,
                currentSubtaskId = "1",
                subtaskStartedAt = subtaskStarted,
                updatedAt = clock.now(),
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefs = FakePreferenceCache(refreshIntervalSeconds = 60)
        val coordinator = StatusRefreshCoordinator(repo, prefs, scope, Path.of(project.basePath!!))
        val vm = SkillBillStatusViewModel(coordinator, clock, scope, tickIntervalMs = 50)
        val widget = SkillBillStatusBarWidget(project, vm, clock, tickIntervalMs = 50)
        widget.activate()
        assertTrue(widget.isTickerRunning())

        // Wait off the EDT so ViewModel → widget invokeLater updates can flush.
        runBlocking(Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + 2_000
            while (refreshCount.get() < 1 && System.currentTimeMillis() < deadline) {
                delay(20)
            }
            // Allow collectLatest to assign latestState after the outcome emit.
            delay(50)
        }
        assertTrue("activate must poll once", refreshCount.get() >= 1)

        // Synchronous re-anchor of authoritative starts for the local UI ticker.
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
                subtaskStartedAt = subtaskStarted,
                lastUpdated = clock.now(),
            ),
        )

        val callsAfterActivate = refreshCount.get()
        clock.advanceSeconds(30)
        val afterTick = widget.tickOnceForTest()
        assertEquals(
            "local tick must not launch an extra CLI poll",
            callsAfterActivate,
            refreshCount.get(),
        )
        assertTrue(
            "goal elapsed after local tick: ${afterTick.details.goalElapsedText}",
            afterTick.details.goalElapsedText.contains("1m 30s"),
        )

        val beforeClick = refreshCount.get()
        widget.clickForTest()
        assertEquals(SkillBillStatusBarWidget.ClickKind.REFRESH_AND_DETAILS, widget.lastClickKind())
        runBlocking(Dispatchers.Default) { delay(100) }
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

    fun testPopupPanelIsConstructibleWithoutShowingAPopup() {
        val widget = newWidget()
        val eligible = SkillBillStatusBarPresentation.map(activeUiState())
        val built = widget.buildPopupContent(eligible)

        val insets = built.panel.border.getBorderInsets(built.panel)
        assertTrue("panel must be padded", insets.top > 0 && insets.left > 0)
        assertNotNull("action row must be present", built.actionRow)
        assertNotNull("separator must be present", built.separator)
        assertEquals("exactly two controls when eligible", 2, built.buttons.size)
        built.buttons.values.forEach { button ->
            assertTrue("accessible name must be non-blank", button.accessibleContext.accessibleName.isNotBlank())
            assertTrue("buttons must be keyboard reachable", button.isFocusable)
        }

        // The rendered lines are exactly today's detail lines, in today's order.
        val details = eligible.details
        assertEquals(
            listOf(
                "State" to details.lifecycleState,
                "Issue" to details.issueKey,
                "Workflow" to details.workflowId,
                "Step" to details.stepLabel,
                "Progress" to details.progressText,
                "Goal ${details.elapsedNoun}" to details.goalElapsedText,
                "Subtask ${details.elapsedNoun}" to details.subtaskElapsedText,
                "" to details.problemSummary,
            ),
            StatusDetailsPopupContent.statusLines(eligible),
        )

        val ineligible = SkillBillStatusBarPresentation.map(SkillBillStatusUiState.Idle())
        val emptyBuilt = widget.buildPopupContent(ineligible)
        assertTrue("no controls for an ineligible state", emptyBuilt.buttons.isEmpty())
        assertNull(emptyBuilt.actionRow)
        widget.dispose()
    }

    fun testDisabledPauseKeepsItsAccessibleNameAndRegisteredRequestText() {
        val widget = newWidget()
        val built = widget.buildPopupContent(
            SkillBillStatusBarPresentation.map(activeUiState(pauseRequested = true)),
        )
        val pause = built.buttonFor(GoalControlKind.PAUSE)!!
        assertFalse("a registered request disables pause", pause.isEnabled)
        assertTrue("text reports the registered request", pause.text.contains("requested", ignoreCase = true))
        assertTrue(pause.accessibleContext.accessibleName.isNotBlank())
        assertTrue("a disabled control stays keyboard reachable", pause.isFocusable)
        widget.dispose()
    }

    fun testActivatingEachControlInvokesItsRepositoryOffTheEdt() {
        val pauseRepo = FakeGoalPauseRepository()
        val stopRepo = FakeGoalStopRepository()
        val widget = newWidget(pauseRepo, stopRepo)
        val built = widget.buildPopupContent(SkillBillStatusBarPresentation.map(activeUiState()))

        built.buttonFor(GoalControlKind.STOP)!!.doClick()
        built.buttonFor(GoalControlKind.PAUSE)!!.doClick()
        runBlocking(Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + 2_000
            while ((stopRepo.invocations.isEmpty() || pauseRepo.invocations.isEmpty()) &&
                System.currentTimeMillis() < deadline
            ) {
                delay(20)
            }
        }

        assertEquals("stop invoked exactly once", 1, stopRepo.invocations.size)
        assertEquals("pause invoked exactly once", 1, pauseRepo.invocations.size)
        for (invocation in stopRepo.invocations + pauseRepo.invocations) {
            assertEquals("SKILL-168", invocation.issueKey)
            assertEquals(Path.of(project.basePath!!), invocation.projectRoot)
            assertFalse(
                "the mutating call must not run on the EDT: ${invocation.threadName}",
                invocation.threadName.contains("AWT-EventQueue"),
            )
        }
        widget.dispose()
    }

    fun testFailedMutationRendersItsSummaryAndLeavesPollingRunning() {
        val refreshCount = AtomicInteger(0)
        val clock = ControllableClock(Instant.parse("2026-08-07T12:00:00Z"))
        val repo = FakeStatusRepository {
            refreshCount.incrementAndGet()
            SkillBillStatusOutcome.Idle(clock.now(), "idle")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefs = FakePreferenceCache(refreshIntervalSeconds = 60)
        val coordinator = StatusRefreshCoordinator(repo, prefs, scope, Path.of(project.basePath!!))
        val vm = SkillBillStatusViewModel(coordinator, clock, scope, tickIntervalMs = 50)
        val stopRepo = FakeGoalStopRepository(GoalStopOutcome.Failed("Skill Bill declined the stop request"))
        val widget = SkillBillStatusBarWidget(
            project,
            vm,
            clock,
            tickIntervalMs = 50,
            goalPauseRepository = FakeGoalPauseRepository(),
            goalStopRepository = stopRepo,
        )
        widget.activate()
        val built = widget.buildPopupContent(SkillBillStatusBarPresentation.map(activeUiState()))
        built.buttonFor(GoalControlKind.STOP)!!.doClick()

        // The summary arrives through invokeLater and this test body runs on the EDT, so
        // the queue must be pumped rather than blocked while waiting for it.
        val messageDeadline = System.currentTimeMillis() + 2_000
        while (built.messageText() == null && System.currentTimeMillis() < messageDeadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        assertEquals("Skill Bill declined the stop request", built.messageText())
        assertTrue("a failed mutation must not stop the ticker", widget.isTickerRunning())

        val before = refreshCount.get()
        widget.clickForTest()
        runBlocking(Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + 2_000
            while (refreshCount.get() <= before && System.currentTimeMillis() < deadline) {
                delay(20)
            }
        }
        assertTrue("polling continues after a failure", refreshCount.get() > before)

        widget.dispose()
        vm.dispose()
        coordinator.dispose()
        scope.cancel()
    }

    private fun newWidget(
        pauseRepo: FakeGoalPauseRepository = FakeGoalPauseRepository(),
        stopRepo: FakeGoalStopRepository = FakeGoalStopRepository(),
    ): SkillBillStatusBarWidget {
        val clock = ControllableClock(Instant.parse("2026-08-07T12:00:00Z"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = FakeStatusRepository { SkillBillStatusOutcome.Idle(clock.now(), "idle") }
        val coordinator = StatusRefreshCoordinator(
            repo,
            FakePreferenceCache(refreshIntervalSeconds = 60),
            scope,
            Path.of(project.basePath!!),
        )
        val vm = SkillBillStatusViewModel(coordinator, clock, scope, tickIntervalMs = 50)
        return SkillBillStatusBarWidget(
            project,
            vm,
            clock,
            tickIntervalMs = 50,
            goalPauseRepository = pauseRepo,
            goalStopRepository = stopRepo,
        )
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

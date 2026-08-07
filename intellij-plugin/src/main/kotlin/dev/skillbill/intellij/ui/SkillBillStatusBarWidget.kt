package dev.skillbill.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import dev.skillbill.intellij.composition.SkillBillProjectStatusService
import dev.skillbill.intellij.domain.StatusClock
import dev.skillbill.intellij.presentation.SkillBillStatusBarPresentation
import dev.skillbill.intellij.presentation.SkillBillStatusUiState
import dev.skillbill.intellij.presentation.SkillBillStatusViewModel
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.SwingConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Thin status-bar consumer of the project-scoped ViewModel. Owns local UI ticking
 * and EDT updates only — no CLI, JSON, or workflow mutation.
 */
class SkillBillStatusBarWidget(
    private val project: Project,
    private val viewModel: SkillBillStatusViewModel =
        project.getService(SkillBillProjectStatusService::class.java).viewModel,
    private val clock: StatusClock = StatusClock.system(),
    private val tickIntervalMs: Long = 1_000L,
) : CustomStatusBarWidget, StatusBarWidget.WidgetPresentation {
    private val activityIcon = AnimatedIcon.Default()
    private val idleIcon = AllIcons.General.Information
    private val component = JLabel("", idleIcon, SwingConstants.CENTER).apply {
        border = JBUI.Borders.empty(0, 6)
        isOpaque = false
        toolTipText = "Skill Bill"
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    }

    private var statusBar: StatusBar? = null
    private var latestState: SkillBillStatusUiState = SkillBillStatusUiState.Idle()
    private var latestPresentation: SkillBillStatusBarPresentation.MappedPresentation =
        SkillBillStatusBarPresentation.map(latestState)
    private var subscriptionScope: CoroutineScope? = null
    private var ticker: Alarm? = null
    private var disposed = false
    private var activated = false
    private var lastClickKind: ClickKind? = null

    init {
        component.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button != MouseEvent.BUTTON1) return
                    onClick(e.component, e.point)
                }
            },
        )
        applyPresentation(latestPresentation)
    }

    override fun ID(): String = SkillBillStatusBarIds.ID

    override fun getComponent(): JLabel = component

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getTooltipText(): String = latestPresentation.tooltipText

    override fun getClickConsumer(): com.intellij.util.Consumer<MouseEvent> =
        com.intellij.util.Consumer { event -> onClick(event.component, event.point) }

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        activate()
    }

    /** Starts ViewModel consumption and the local UI ticker (idempotent). */
    internal fun activate() {
        if (activated || disposed || project.isDisposed) return
        activated = true
        viewModel.onWidgetActivated()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        subscriptionScope = scope
        scope.launch {
            viewModel.uiState.collectLatest { state ->
                latestState = state
                val mapped = SkillBillStatusBarPresentation.map(state, clock.now())
                scheduleUi { applyPresentation(mapped) }
            }
        }
        startTicker()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        stopTicker()
        subscriptionScope?.cancel()
        subscriptionScope = null
        if (activated) {
            viewModel.onWidgetDeactivated()
            activated = false
        }
        statusBar = null
        component.icon = null
        component.text = ""
        component.toolTipText = null
    }

    internal fun currentPresentation(): SkillBillStatusBarPresentation.MappedPresentation =
        latestPresentation

    internal fun isTickerRunning(): Boolean = ticker != null && !disposed

    internal fun lastClickKind(): ClickKind? = lastClickKind

    internal fun tickOnceForTest(): SkillBillStatusBarPresentation.MappedPresentation {
        val mapped = SkillBillStatusBarPresentation.map(latestState, clock.now())
        applyPresentation(mapped)
        return mapped
    }

    internal fun clickForTest() {
        onClick(component, Point(0, 0))
    }

    internal fun replaceLatestStateForTest(state: SkillBillStatusUiState) {
        latestState = state
        applyPresentation(SkillBillStatusBarPresentation.map(state, clock.now()))
    }

    private fun onClick(owner: Component?, point: Point?) {
        if (disposed || project.isDisposed) return
        // Coalesced immediate refresh — read-only, no workflow mutation.
        viewModel.refresh()
        lastClickKind = ClickKind.REFRESH_AND_DETAILS
        val details = latestPresentation.details
        val lines = buildList {
            add("Skill Bill details")
            add("State: ${details.lifecycleState}")
            details.issueKey?.let { add("Issue: $it") }
            details.workflowId?.let { add("Workflow: $it") }
            details.stepLabel?.let { add("Step: $it") }
            details.progressText?.let { add("Progress: $it") }
            add("Goal ${details.elapsedNoun}: ${details.goalElapsedText}")
            add("Subtask ${details.elapsedNoun}: ${details.subtaskElapsedText}")
            details.lastUpdateText?.let { add("Last update: $it") }
            details.problemSummary?.let { add(it) }
            details.staleNote?.let { add(it) }
        }
        val html = lines.joinToString("<br/>") { escapeXml(it) }
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(JLabel("<html>$html</html>"), null)
            .setRequestFocus(false)
            .setCancelOnClickOutside(true)
            .setTitle("Skill Bill")
            .createPopup()
        val anchor = owner ?: component
        val relative = point ?: Point(anchor.width / 2, 0)
        if (ApplicationManager.getApplication()?.isUnitTestMode == true) {
            // Avoid showing Swing popups in headless unit tests; refresh already ran.
            Disposer.dispose(popup)
            return
        }
        popup.show(RelativePoint(anchor, relative))
    }

    private fun startTicker() {
        stopTicker()
        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
        ticker = alarm
        scheduleNextTick(alarm)
    }

    private fun scheduleNextTick(alarm: Alarm) {
        if (disposed) return
        alarm.addRequest(
            {
                if (disposed) return@addRequest
                val mapped = SkillBillStatusBarPresentation.map(latestState, clock.now())
                applyPresentation(mapped)
                scheduleNextTick(alarm)
            },
            tickIntervalMs,
        )
    }

    private fun stopTicker() {
        ticker?.cancelAllRequests()
        ticker = null
    }

    private fun scheduleUi(block: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app == null || app.isDispatchThread) {
            block()
        } else {
            app.invokeLater {
                if (!disposed && !project.isDisposed) block()
            }
        }
    }

    private fun applyPresentation(presentation: SkillBillStatusBarPresentation.MappedPresentation) {
        latestPresentation = presentation
        component.text = presentation.barText
        component.toolTipText = presentation.tooltipText
        component.icon = if (presentation.showActivityAnimation) activityIcon else idleIcon
        component.accessibleContext.accessibleName = presentation.accessibleName
        component.accessibleContext.accessibleDescription = presentation.accessibleDescription
        statusBar?.updateWidget(ID())
    }

    private fun escapeXml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    enum class ClickKind {
        REFRESH_AND_DETAILS,
    }
}

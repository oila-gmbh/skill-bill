package dev.skillbill.intellij.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.annotations.Nls

/**
 * Registers the Skill Bill status-bar widget for normal project windows with a
 * resolvable project context. Extension id, factory id, and widget id are identical.
 */
class SkillBillStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = SkillBillStatusBarIds.ID

    @Nls
    override fun getDisplayName(): String = SkillBillStatusBarIds.DISPLAY_NAME

    override fun isAvailable(project: Project): Boolean =
        !project.isDisposed &&
            !project.isDefault &&
            !project.basePath.isNullOrBlank()

    override fun createWidget(project: Project): StatusBarWidget =
        SkillBillStatusBarWidget(project)

    override fun createWidget(project: Project, scope: CoroutineScope): StatusBarWidget =
        SkillBillStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

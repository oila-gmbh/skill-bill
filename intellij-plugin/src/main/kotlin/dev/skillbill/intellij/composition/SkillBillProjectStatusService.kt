package dev.skillbill.intellij.composition

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.skillbill.intellij.application.StatusRefreshCoordinator
import dev.skillbill.intellij.infrastructure.prefs.IntelliJPreferenceCache
import dev.skillbill.intellij.presentation.SkillBillStatusViewModel
import java.nio.file.Path

/**
 * Project service exposing presentation-facing handles for status-bar (and future
 * tool-window) consumers. Owns the composed graph lifetime; disposal cancels
 * polling and child processes.
 */
class SkillBillProjectStatusService(
    private val project: Project,
) : Disposable {
    private val root: SkillBillStatusCompositionRoot =
        SkillBillStatusCompositionRoot.create(
            projectRoot = Path.of(project.basePath ?: "."),
            preferences = IntelliJPreferenceCache.forProject(project),
            parentDisposable = this,
        )

    val viewModel: SkillBillStatusViewModel
        get() = root.viewModel

    val coordinator: StatusRefreshCoordinator
        get() = root.coordinator

    init {
        Disposer.register(project, this)
    }

    override fun dispose() {
        root.dispose()
    }
}

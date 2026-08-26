package dev.skillbill.intellij.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import dev.skillbill.intellij.composition.SkillBillProjectStatusService
import dev.skillbill.intellij.domain.MAX_REFRESH_INTERVAL_SECONDS
import dev.skillbill.intellij.domain.MIN_REFRESH_INTERVAL_SECONDS
import dev.skillbill.intellij.infrastructure.cli.CliExecutableResolution
import dev.skillbill.intellij.infrastructure.cli.CliExecutableResolver
import dev.skillbill.intellij.infrastructure.cli.CliExecutableSource
import dev.skillbill.intellij.infrastructure.prefs.SkillBillApplicationSettings
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JTextField

/**
 * Settings | Tools | Skill Bill. The CLI path override is otherwise unreachable: a
 * desktop-launched IDE can miss a `skill-bill` that the login shell finds, and without
 * an editor the only correction would be hand-editing `skillBillSettings.xml`.
 */
class SkillBillSettingsConfigurable : BoundConfigurable(DISPLAY_NAME) {
    private val settings: SkillBillApplicationSettings
        get() = service<SkillBillApplicationSettings>()

    override fun createPanel(): DialogPanel = panel {
        row(EXECUTABLE_LABEL) {
            textField()
                .align(AlignX.FILL)
                .bindText(
                    { settings.readCliOverride().orEmpty() },
                    { settings.writeCliOverride(expandUserHome(it)) },
                )
                .validationOnApply { field -> validateExecutable(field) }
                .comment(resolutionComment())
        }
        row(INTERVAL_LABEL) {
            intTextField(MIN_REFRESH_INTERVAL_SECONDS.toInt()..MAX_REFRESH_INTERVAL_SECONDS.toInt())
                .bindIntText(
                    { settings.readRefreshIntervalSeconds().toInt() },
                    { settings.writeRefreshIntervalSeconds(it.toLong()) },
                )
        }
    }

    override fun apply() {
        super.apply()
        refreshOpenProjects()
    }

    private fun validateExecutable(field: JTextField): ValidationInfo? {
        val candidate = expandUserHome(field.text) ?: return null
        val path = try {
            Path.of(candidate)
        } catch (_: Exception) {
            return ValidationInfo(NOT_A_PATH_MESSAGE, field)
        }
        if (!path.isAbsolute) return ValidationInfo(NOT_ABSOLUTE_MESSAGE, field)
        if (!Files.isRegularFile(path)) return ValidationInfo(NOT_A_FILE_MESSAGE, field)
        if (!Files.isExecutable(path)) return ValidationInfo(NOT_EXECUTABLE_MESSAGE, field)
        return null
    }

    private fun resolutionComment(): String =
        when (val resolution = CliExecutableResolver.resolveOverride(settings.readCliOverride())) {
            is CliExecutableResolution.Found -> when (resolution.source) {
                CliExecutableSource.OVERRIDE -> "Using this override: ${resolution.path}"
                CliExecutableSource.SEARCH_PATH -> "Leave empty to use PATH. Currently resolved: ${resolution.path}"
                CliExecutableSource.INSTALL_DIRECTORY ->
                    "Leave empty to use PATH or the installer directory. Currently resolved: ${resolution.path}"
            }

            CliExecutableResolution.Misconfigured -> "This path is not an executable file."
            CliExecutableResolution.Missing -> MISSING_COMMENT
        }

    private fun refreshOpenProjects() {
        ProjectManager.getInstance().openProjects.forEach { project ->
            project.getServiceIfCreated(SkillBillProjectStatusService::class.java)?.coordinator?.requestRefresh()
        }
    }

    private fun expandUserHome(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val home = System.getProperty("user.home") ?: return value
        return when {
            value == "~" -> home
            value.startsWith("~/") -> home + value.removePrefix("~")
            else -> value
        }
    }

    companion object {
        const val DISPLAY_NAME: String = "Skill Bill"

        private const val EXECUTABLE_LABEL = "CLI executable path:"
        private const val INTERVAL_LABEL = "Status refresh interval (seconds):"
        private const val NOT_A_PATH_MESSAGE = "Enter a filesystem path to the skill-bill executable."
        private const val NOT_ABSOLUTE_MESSAGE = "Enter an absolute path."
        private const val NOT_A_FILE_MESSAGE = "No file at this path."
        private const val NOT_EXECUTABLE_MESSAGE = "This file is not executable."
        private const val MISSING_COMMENT =
            "No skill-bill executable found on PATH or in the installer directory. " +
                "Enter its absolute path, for example ~/.local/bin/skill-bill."
    }
}

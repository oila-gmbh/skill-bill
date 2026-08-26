package dev.skillbill.intellij.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.ui.DialogPanel
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import dev.skillbill.intellij.infrastructure.cli.CliExecutableResolution
import dev.skillbill.intellij.infrastructure.cli.CliExecutableResolver
import dev.skillbill.intellij.infrastructure.cli.CliExecutableSource
import dev.skillbill.intellij.infrastructure.prefs.SkillBillApplicationSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * The settings editor is the only in-IDE way to point the plugin at a CLI its PATH
 * lookup cannot see, so an editor that fails to persist the entered path leaves the
 * status widget permanently unavailable.
 */
class SkillBillSettingsConfigurableFixtureTest : BasePlatformTestCase() {
    private lateinit var launcher: Path

    override fun setUp() {
        super.setUp()
        launcher = Files.createTempDirectory("skill-bill-bin").resolve(CliExecutableResolver.EXECUTABLE_NAME)
        Files.writeString(launcher, "#!/bin/sh\n")
        launcher.toFile().setExecutable(true)
    }

    override fun tearDown() {
        try {
            service<SkillBillApplicationSettings>().writeCliOverride(null)
        } finally {
            super.tearDown()
        }
    }

    fun testAppliedOverridePersistsAndResolves() {
        val configurable = SkillBillSettingsConfigurable()
        val panel = configurable.createComponent() as DialogPanel
        pathField(panel).text = launcher.toString()

        configurable.apply()

        assertEquals(launcher.toString(), service<SkillBillApplicationSettings>().readCliOverride())
        assertEquals(
            CliExecutableResolution.Found(launcher.toString(), CliExecutableSource.OVERRIDE),
            CliExecutableResolver.resolveOverride(service<SkillBillApplicationSettings>().readCliOverride()),
        )
        configurable.disposeUIResources()
    }

    private fun pathField(panel: DialogPanel): JBTextField =
        UIUtil.findComponentsOfType(panel, JBTextField::class.java).first()
}

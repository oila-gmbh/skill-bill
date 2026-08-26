package dev.skillbill.intellij.infrastructure.cli

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Resolution runs against a fake environment: a desktop-launched IDE inherits the
 * session PATH, not the login shell's, so these cases are the difference between a
 * working status widget and a permanently unavailable one.
 */
class CliExecutableResolverTest {
    @Test
    fun `resolves the installer launcher when it is absent from PATH`() {
        val binDir = tempBinDirWithLauncher()
        val environment = environmentOf(
            "PATH" to listOf("/usr/bin", "/bin").joinToString(File.pathSeparator),
            "HOME" to binDir.parent.parent.toString(),
        )

        val resolution = CliExecutableResolver.resolveOverride(null, environment)

        assertEquals(
            CliExecutableResolution.Found(
                binDir.resolve(CliExecutableResolver.EXECUTABLE_NAME).toString(),
                CliExecutableSource.INSTALL_DIRECTORY,
            ),
            resolution,
        )
    }

    @Test
    fun `resolves the launcher directory named by SKILL_BILL_BIN_DIR`() {
        val binDir = tempBinDirWithLauncher()
        val environment = environmentOf(
            "PATH" to "/usr/bin",
            "SKILL_BILL_BIN_DIR" to binDir.toString(),
        )

        val resolution = CliExecutableResolver.resolveOverride(null, environment)

        assertEquals(
            CliExecutableResolution.Found(
                binDir.resolve(CliExecutableResolver.EXECUTABLE_NAME).toString(),
                CliExecutableSource.INSTALL_DIRECTORY,
            ),
            resolution,
        )
    }

    @Test
    fun `an unusable override never falls back to a discovered launcher`() {
        val binDir = tempBinDirWithLauncher()
        val environment = environmentOf(
            "PATH" to binDir.toString(),
            "HOME" to binDir.parent.parent.toString(),
        )

        val resolution = CliExecutableResolver.resolveOverride(
            binDir.resolve("not-installed").toString(),
            environment,
        )

        assertEquals(CliExecutableResolution.Misconfigured, resolution)
    }

    @Test
    fun `reports missing when neither PATH nor the launcher directories carry it`() {
        val emptyHome = Files.createTempDirectory("skill-bill-home")
        val environment = environmentOf(
            "PATH" to Files.createTempDirectory("skill-bill-path").toString(),
            "HOME" to emptyHome.toString(),
        )

        val resolution = CliExecutableResolver.resolveOverride(null, environment)

        assertEquals(CliExecutableResolution.Missing, resolution)
    }

    private fun environmentOf(vararg entries: Pair<String, String>): CliEnvironment {
        val values = entries.toMap()
        return CliEnvironment { name -> values[name] }
    }

    private fun tempBinDirWithLauncher(): Path {
        val home = Files.createTempDirectory("skill-bill-home")
        val binDir = Files.createDirectories(home.resolve(".local").resolve("bin"))
        val launcher = binDir.resolve(CliExecutableResolver.EXECUTABLE_NAME)
        Files.writeString(launcher, "#!/bin/sh\n")
        launcher.toFile().setExecutable(true)
        return binDir
    }
}

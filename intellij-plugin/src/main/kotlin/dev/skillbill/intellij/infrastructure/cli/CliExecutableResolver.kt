package dev.skillbill.intellij.infrastructure.cli

import com.intellij.util.EnvironmentUtil
import dev.skillbill.intellij.application.PreferenceCachePort
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

sealed class CliExecutableResolution {
    data class Found(val path: String, val source: CliExecutableSource) : CliExecutableResolution()
    data object Missing : CliExecutableResolution()
    data object Misconfigured : CliExecutableResolution()
}

enum class CliExecutableSource {
    OVERRIDE,
    SEARCH_PATH,
    INSTALL_DIRECTORY,
}

/**
 * Single lookup vocabulary for environment values, so resolution is testable without
 * an IDE and without mutating the JVM process environment.
 */
fun interface CliEnvironment {
    fun value(name: String): String?
}

/**
 * Resolves the `skill-bill` launcher for every CLI adapter.
 *
 * A desktop-launched IDE inherits the session environment, not the login shell's, so
 * `System.getenv("PATH")` alone misses the installer default `~/.local/bin` and every
 * other shell-profile entry. Lookup therefore merges the platform's shell-aware
 * environment with the process environment, then probes the installer's launcher
 * directory (`SKILL_BILL_BIN_DIR`, default `~/.local/bin`) before reporting the
 * executable missing.
 */
object CliExecutableResolver {
    const val EXECUTABLE_NAME: String = "skill-bill"

    private const val PATH_VARIABLE = "PATH"
    private const val BIN_DIR_VARIABLE = "SKILL_BILL_BIN_DIR"
    private const val HOME_VARIABLE = "HOME"

    fun resolve(
        preferences: PreferenceCachePort,
        environment: CliEnvironment = platformEnvironment(),
    ): CliExecutableResolution = resolveOverride(preferences.getCliExecutableOverride(), environment)

    fun resolveOverride(
        rawOverride: String?,
        environment: CliEnvironment = platformEnvironment(),
    ): CliExecutableResolution {
        val override = rawOverride?.trim()?.takeIf { it.isNotEmpty() }
        if (override != null) {
            val path = try {
                Path.of(override)
            } catch (_: Exception) {
                return CliExecutableResolution.Misconfigured
            }
            return if (isRunnable(path)) {
                CliExecutableResolution.Found(path.toString(), CliExecutableSource.OVERRIDE)
            } else {
                // Override set but unusable — do not fall back to PATH.
                CliExecutableResolution.Misconfigured
            }
        }
        findOnPath(EXECUTABLE_NAME, environment)?.let {
            return CliExecutableResolution.Found(it, CliExecutableSource.SEARCH_PATH)
        }
        findInInstallDirectories(EXECUTABLE_NAME, environment)?.let {
            return CliExecutableResolution.Found(it, CliExecutableSource.INSTALL_DIRECTORY)
        }
        return CliExecutableResolution.Missing
    }

    fun findOnPath(name: String, environment: CliEnvironment = platformEnvironment()): String? =
        splitSearchPath(environment.value(PATH_VARIABLE)).firstNotNullOfOrNull { directory ->
            runnableIn(directory, name)
        }

    fun installDirectories(environment: CliEnvironment = platformEnvironment()): List<String> = buildList {
        environment.value(BIN_DIR_VARIABLE)?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        environment.value(HOME_VARIABLE)?.trim()?.takeIf { it.isNotEmpty() }?.let { home ->
            add(Path.of(home, ".local", "bin").toString())
        }
    }.distinct()

    /**
     * Merges the platform's shell-aware environment with the process environment instead of
     * choosing one: either can carry an entry the other lacks, and a missed entry is the
     * failure this resolver exists to prevent.
     */
    fun platformEnvironment(): CliEnvironment = CliEnvironment { name ->
        val shellValue = shellEnvironmentValue(name)
        val processValue = System.getenv(name) ?: fallbackProcessValue(name)
        if (name == PATH_VARIABLE) {
            mergeSearchPaths(shellValue, processValue)
        } else {
            shellValue?.takeIf { it.isNotBlank() } ?: processValue
        }
    }

    private fun findInInstallDirectories(name: String, environment: CliEnvironment): String? =
        installDirectories(environment).firstNotNullOfOrNull { directory -> runnableIn(directory, name) }

    private fun runnableIn(directory: String, name: String): String? {
        val candidate = try {
            Path.of(directory, name)
        } catch (_: Exception) {
            return null
        }
        return if (isRunnable(candidate)) candidate.toString() else null
    }

    private fun isRunnable(path: Path): Boolean = Files.isRegularFile(path) && Files.isExecutable(path)

    private fun shellEnvironmentValue(name: String): String? =
        try {
            EnvironmentUtil.getValue(name)
        } catch (_: Throwable) {
            null
        }

    private fun fallbackProcessValue(name: String): String? =
        if (name == HOME_VARIABLE) System.getProperty("user.home") else null

    private fun mergeSearchPaths(first: String?, second: String?): String? {
        val entries = (splitSearchPath(first) + splitSearchPath(second)).distinct()
        return entries.takeIf { it.isNotEmpty() }?.joinToString(File.pathSeparator)
    }

    private fun splitSearchPath(raw: String?): List<String> =
        raw?.split(File.pathSeparatorChar)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
}

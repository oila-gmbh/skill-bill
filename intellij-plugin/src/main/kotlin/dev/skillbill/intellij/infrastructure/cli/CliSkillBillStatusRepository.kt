package dev.skillbill.intellij.infrastructure.cli

import com.intellij.openapi.application.ApplicationManager
import dev.skillbill.intellij.application.PreferenceCachePort
import dev.skillbill.intellij.application.StatusRepository
import dev.skillbill.intellij.domain.DEFAULT_CLI_TIMEOUT_MS
import dev.skillbill.intellij.domain.DEFAULT_STDERR_LIMIT_BYTES
import dev.skillbill.intellij.domain.DEFAULT_STDOUT_LIMIT_BYTES
import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import dev.skillbill.intellij.domain.StatusClock
import dev.skillbill.intellij.domain.StatusDiagnostic
import dev.skillbill.intellij.domain.UnavailableReason
import java.nio.file.Files
import java.nio.file.Path

sealed class CliExecutableResolution {
    data class Found(val path: String) : CliExecutableResolution()
    data object Missing : CliExecutableResolution()
    data object Misconfigured : CliExecutableResolution()
}

/**
 * CLI-backed [StatusRepository]. Runs
 * `skill-bill work status --repo-root <canonical> --format json` off the EDT.
 */
class CliSkillBillStatusRepository(
    private val preferences: PreferenceCachePort,
    private val processRunner: ProcessRunner,
    private val clock: StatusClock = StatusClock.system(),
    private val executableResolver: () -> CliExecutableResolution = { resolveExecutable(preferences) },
    private val timeoutMs: Long = DEFAULT_CLI_TIMEOUT_MS,
    private val stdoutLimitBytes: Int = DEFAULT_STDOUT_LIMIT_BYTES,
    private val stderrLimitBytes: Int = DEFAULT_STDERR_LIMIT_BYTES,
) : StatusRepository {
    override suspend fun fetchStatus(projectRoot: Path): SkillBillStatusOutcome {
        val observedAt = clock.now()
        val executable = when (val resolution = executableResolver()) {
            is CliExecutableResolution.Found -> resolution.path
            CliExecutableResolution.Missing ->
                return SkillBillStatusOutcome.Unavailable(
                    observedAt = observedAt,
                    summary = "Skill Bill CLI executable not found",
                    reasonCode = UnavailableReason.MISSING_EXECUTABLE,
                    diagnostic = StatusDiagnostic(reasonCode = "missing_executable"),
                )
            CliExecutableResolution.Misconfigured ->
                return SkillBillStatusOutcome.Unavailable(
                    observedAt = observedAt,
                    summary = "Skill Bill CLI executable override is not usable",
                    reasonCode = UnavailableReason.MISCONFIGURED,
                    diagnostic = StatusDiagnostic(reasonCode = "misconfigured_executable"),
                )
        }
        val canonicalRoot = try {
            projectRoot.toAbsolutePath().normalize().toRealPath()
        } catch (_: Exception) {
            return SkillBillStatusOutcome.Unavailable(
                observedAt = observedAt,
                summary = "Project root is not a usable path",
                reasonCode = UnavailableReason.INVALID_REPOSITORY_INPUT,
                diagnostic = StatusDiagnostic(reasonCode = "invalid_repository_input"),
            )
        }

        val result = try {
            processRunner.runCoalesced(
                ProcessSpec(
                    command = listOf(
                        executable,
                        "work",
                        "status",
                        "--repo-root",
                        canonicalRoot.toString(),
                        "--format",
                        "json",
                    ),
                    timeoutMs = timeoutMs,
                    stdoutLimitBytes = stdoutLimitBytes,
                    stderrLimitBytes = stderrLimitBytes,
                ),
            )
        } catch (_: Exception) {
            // Never leak command output or paths from the failure surface.
            return SkillBillStatusOutcome.Unavailable(
                observedAt = observedAt,
                summary = "Skill Bill status command failed to start",
                reasonCode = UnavailableReason.PROCESS_FAILURE,
                diagnostic = StatusDiagnostic(reasonCode = "process_start_failure"),
            )
        }

        if (result.cancelled) {
            return SkillBillStatusOutcome.Unavailable(
                observedAt = observedAt,
                summary = "Skill Bill status poll cancelled",
                reasonCode = UnavailableReason.CANCELLED,
                diagnostic = StatusDiagnostic(cancelled = true, reasonCode = "cancelled"),
            )
        }
        if (result.timedOut) {
            return SkillBillStatusOutcome.Unavailable(
                observedAt = observedAt,
                summary = "Skill Bill status poll timed out",
                reasonCode = UnavailableReason.TIMEOUT,
                diagnostic = StatusDiagnostic(timedOut = true, reasonCode = "timeout"),
            )
        }
        return IdeStatusJsonMapper.map(
            stdout = result.stdout,
            observedAt = observedAt,
            exitCode = result.exitCode,
        )
    }

    companion object {
        fun intellijEdtGuard(): EdtGuard = EdtGuard {
            val app = ApplicationManager.getApplication()
            if (app != null && app.isDispatchThread) {
                error("Skill Bill status CLI must not run on the EDT")
            }
        }

        fun resolveExecutable(preferences: PreferenceCachePort): CliExecutableResolution {
            val override = preferences.getCliExecutableOverride()?.trim()?.takeIf { it.isNotEmpty() }
            if (override != null) {
                val path = Path.of(override)
                return if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                    CliExecutableResolution.Found(path.toString())
                } else {
                    // Override set but unusable — do not fall back to PATH.
                    CliExecutableResolution.Misconfigured
                }
            }
            val onPath = findOnPath("skill-bill")
            return if (onPath != null) {
                CliExecutableResolution.Found(onPath)
            } else {
                CliExecutableResolution.Missing
            }
        }

        fun findOnPath(name: String): String? {
            val pathEnv = System.getenv("PATH") ?: return null
            val separators = if (pathEnv.contains(';') && !pathEnv.contains(':')) ";" else ":"
            return pathEnv.split(separators)
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { Path.of(it, name) }
                .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
                ?.toString()
        }
    }
}

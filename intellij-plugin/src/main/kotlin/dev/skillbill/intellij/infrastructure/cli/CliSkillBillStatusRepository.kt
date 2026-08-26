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
import java.nio.file.Path

/**
 * CLI-backed [StatusRepository]. Runs
 * `skill-bill work status --repo-root <canonical> --format json` off the EDT.
 */
class CliSkillBillStatusRepository(
    private val preferences: PreferenceCachePort,
    private val processRunner: ProcessRunner,
    private val clock: StatusClock = StatusClock.system(),
    private val executableResolver: () -> CliExecutableResolution = { CliExecutableResolver.resolve(preferences) },
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
                    summary = MISSING_EXECUTABLE_SUMMARY,
                    reasonCode = UnavailableReason.MISSING_EXECUTABLE,
                    diagnostic = StatusDiagnostic(reasonCode = "missing_executable"),
                )
            CliExecutableResolution.Misconfigured ->
                return SkillBillStatusOutcome.Unavailable(
                    observedAt = observedAt,
                    summary = MISCONFIGURED_EXECUTABLE_SUMMARY,
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
        const val MISSING_EXECUTABLE_SUMMARY: String =
            "Skill Bill CLI not found — set its path in Settings | Tools | Skill Bill"
        const val MISCONFIGURED_EXECUTABLE_SUMMARY: String =
            "Skill Bill CLI path override is not usable — check Settings | Tools | Skill Bill"

        fun intellijEdtGuard(): EdtGuard = EdtGuard {
            val app = ApplicationManager.getApplication()
            if (app != null && app.isDispatchThread) {
                error("Skill Bill status CLI must not run on the EDT")
            }
        }
    }
}

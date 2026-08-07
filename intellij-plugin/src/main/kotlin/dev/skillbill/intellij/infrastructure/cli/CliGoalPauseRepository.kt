package dev.skillbill.intellij.infrastructure.cli

import dev.skillbill.intellij.application.GoalPauseOutcome
import dev.skillbill.intellij.application.GoalPauseRepository
import dev.skillbill.intellij.application.PreferenceCachePort
import dev.skillbill.intellij.domain.DEFAULT_CLI_TIMEOUT_MS
import dev.skillbill.intellij.domain.DEFAULT_STDERR_LIMIT_BYTES
import dev.skillbill.intellij.domain.DEFAULT_STDOUT_LIMIT_BYTES
import java.nio.file.Path

/**
 * CLI-backed [GoalPauseRepository]. Runs
 * `skill-bill goal pause <issue-key> --repo-root <canonical>` off the EDT.
 *
 * Runs on a [ProcessRunner] of its own: [ProcessRunner.runCoalesced] coalesces by
 * instance, so sharing the poll runner would let a pause join an in-flight status poll
 * and return that poll's exit code as if the pause had landed.
 */
class CliGoalPauseRepository(
    private val preferences: PreferenceCachePort,
    private val processRunner: ProcessRunner,
    private val executableResolver: () -> CliExecutableResolution = {
        CliSkillBillStatusRepository.resolveExecutable(preferences)
    },
    private val timeoutMs: Long = DEFAULT_CLI_TIMEOUT_MS,
) : GoalPauseRepository {
    override suspend fun requestPause(projectRoot: Path, issueKey: String): GoalPauseOutcome {
        val key = issueKey.trim()
        if (key.isEmpty()) return GoalPauseOutcome.Failed("No issue key to pause")
        val executable = when (val resolution = executableResolver()) {
            is CliExecutableResolution.Found -> resolution.path
            CliExecutableResolution.Missing -> return GoalPauseOutcome.Failed("Skill Bill CLI executable not found")
            CliExecutableResolution.Misconfigured ->
                return GoalPauseOutcome.Failed("Skill Bill CLI executable override is not usable")
        }
        val canonicalRoot = try {
            projectRoot.toAbsolutePath().normalize().toRealPath()
        } catch (_: Exception) {
            return GoalPauseOutcome.Failed("Project root is not a usable path")
        }

        val result = try {
            processRunner.runCoalesced(
                ProcessSpec(
                    command = listOf(executable, "goal", "pause", key, "--repo-root", canonicalRoot.toString()),
                    timeoutMs = timeoutMs,
                    stdoutLimitBytes = DEFAULT_STDOUT_LIMIT_BYTES,
                    stderrLimitBytes = DEFAULT_STDERR_LIMIT_BYTES,
                ),
            )
        } catch (_: Exception) {
            // Never leak command output or paths from the failure surface.
            return GoalPauseOutcome.Failed("Pause request failed to start")
        }

        return when {
            result.cancelled -> GoalPauseOutcome.Failed("Pause request cancelled")
            result.timedOut -> GoalPauseOutcome.Failed("Pause request timed out")
            result.exitCode == 0 -> GoalPauseOutcome.Requested
            // A non-zero exit is the runtime declining (no running goal, unknown key). The
            // reason is not surfaced verbatim; the next poll shows the authoritative state.
            else -> GoalPauseOutcome.Failed("Skill Bill declined the pause request")
        }
    }
}

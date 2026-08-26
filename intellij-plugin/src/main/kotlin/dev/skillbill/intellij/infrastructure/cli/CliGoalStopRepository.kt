package dev.skillbill.intellij.infrastructure.cli

import dev.skillbill.intellij.application.GoalStopOutcome
import dev.skillbill.intellij.application.GoalStopRepository
import dev.skillbill.intellij.application.PreferenceCachePort
import dev.skillbill.intellij.domain.DEFAULT_CLI_TIMEOUT_MS
import dev.skillbill.intellij.domain.DEFAULT_STDERR_LIMIT_BYTES
import dev.skillbill.intellij.domain.DEFAULT_STDOUT_LIMIT_BYTES
import dev.skillbill.intellij.domain.GOAL_STOP_VERB
import dev.skillbill.intellij.domain.REPO_ROOT_OPTION
import java.nio.file.Path

/**
 * CLI-backed [GoalStopRepository]. Runs
 * `skill-bill goal stop <issue-key> --repo-root <canonical>` off the EDT.
 *
 * Runs on a [ProcessRunner] of its own: [ProcessRunner.runCoalesced] coalesces by
 * instance, so sharing the poll runner would let a stop join an in-flight status poll
 * and return that poll's exit code as if the stop had landed.
 */
class CliGoalStopRepository(
    private val preferences: PreferenceCachePort,
    private val processRunner: ProcessRunner,
    private val executableResolver: () -> CliExecutableResolution = {
        CliExecutableResolver.resolve(preferences)
    },
    private val timeoutMs: Long = DEFAULT_CLI_TIMEOUT_MS,
) : GoalStopRepository {
    override suspend fun requestStop(projectRoot: Path, issueKey: String): GoalStopOutcome {
        val key = issueKey.trim()
        if (key.isEmpty()) return GoalStopOutcome.Failed("No issue key to stop")
        val executable = when (val resolution = executableResolver()) {
            is CliExecutableResolution.Found -> resolution.path
            CliExecutableResolution.Missing -> return GoalStopOutcome.Failed("Skill Bill CLI executable not found")
            CliExecutableResolution.Misconfigured ->
                return GoalStopOutcome.Failed("Skill Bill CLI executable override is not usable")
        }
        val canonicalRoot = try {
            projectRoot.toAbsolutePath().normalize().toRealPath()
        } catch (_: Exception) {
            return GoalStopOutcome.Failed("Project root is not a usable path")
        }

        val result = try {
            processRunner.runCoalesced(
                ProcessSpec(
                    command = listOf(executable) + GOAL_STOP_VERB + listOf(key, REPO_ROOT_OPTION, canonicalRoot.toString()),
                    timeoutMs = timeoutMs,
                    stdoutLimitBytes = DEFAULT_STDOUT_LIMIT_BYTES,
                    stderrLimitBytes = DEFAULT_STDERR_LIMIT_BYTES,
                ),
            )
        } catch (_: Exception) {
            // Never leak command output or paths from the failure surface.
            return GoalStopOutcome.Failed("Stop request failed to start")
        }

        return when {
            result.cancelled -> GoalStopOutcome.Failed("Stop request cancelled")
            result.timedOut -> GoalStopOutcome.Failed("Stop request timed out")
            result.exitCode == 0 -> GoalStopOutcome.Requested
            // A non-zero exit is the runtime declining (no live goal, identity mismatch).
            // The reason is not surfaced verbatim; the next poll shows the real state.
            else -> GoalStopOutcome.Failed("Skill Bill declined the stop request")
        }
    }
}

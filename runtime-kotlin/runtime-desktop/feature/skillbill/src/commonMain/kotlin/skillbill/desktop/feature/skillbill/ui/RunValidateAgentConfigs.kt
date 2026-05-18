package skillbill.desktop.feature.skillbill.ui

/**
 * Platform-specific runner for `scripts/validate_agent_configs`. The JVM `actual` shells out
 * with the hardening posture documented in the planning notes:
 *
 * - `redirectErrorStream(true)` so stderr is interleaved with stdout deterministically.
 * - Drains the merged stream from a daemon thread into an 8 MiB cap so a runaway script never
 *   exhausts heap.
 * - UTF-8 decoding.
 * - Scrubs `GIT_DIR`, `GIT_WORK_TREE`, `GIT_INDEX_FILE`, every `GIT_CONFIG*` and `GIT_TRACE*`,
 *   plus `GIT_EXTERNAL_DIFF`, `GIT_PAGER`, `GIT_EDITOR`, `GIT_SSH_COMMAND`, `GIT_ASKPASS`, and
 *   sets `GIT_TERMINAL_PROMPT=0` to refuse interactive prompts.
 * - F-003-RELIABILITY-CANCEL: declared `suspend` so the implementation can wrap the blocking
 *   `process.waitFor` in [kotlinx.coroutines.runInterruptible] and honor coroutine cancellation.
 */
expect suspend fun runValidateAgentConfigs(repoRootAbsolutePath: String): ValidateAgentConfigsRunResult

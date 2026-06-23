package skillbill.ports.taskruntime

import java.nio.file.Path

/**
 * Completion-time reconciliation writer for the single-spec `## Status` block `Agent:` line.
 *
 * Idempotent and additive: inserts the `Agent:` line when absent and updates it in place when
 * present, never duplicating it on re-run. A no-op when the spec file is absent or has no
 * `## Status` section. The line lives only under `## Status`, so the run-invariants reader that
 * keys off `## Acceptance Criteria` is structurally unaffected.
 */
fun interface FeatureTaskRuntimeSpecStatusWriter {
  fun writeFinalizingAgent(specPath: Path, finalizingAgentId: String)
}

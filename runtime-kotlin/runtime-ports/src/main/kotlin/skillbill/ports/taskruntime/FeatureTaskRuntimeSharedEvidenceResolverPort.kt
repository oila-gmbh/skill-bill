package skillbill.ports.taskruntime

import skillbill.error.ShellContentContractException
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceArtifact
import java.nio.file.Path

/**
 * Derive-once resolution of shared review evidence for one repository checkpoint.
 *
 * The artifact is a derived cache keyed solely on the checkpoint fingerprint. Adapters MUST honour
 * exactly these outcomes:
 *
 * - **fingerprint hit** — a stored artifact whose recorded fingerprint equals the requested one is
 *   returned as-is, with zero repository traversal: [FeatureTaskRuntimeSharedEvidenceDeriver] is
 *   not invoked at all.
 * - **absent** — no stored artifact for the requested fingerprint: derive fresh, persist it, return
 *   it.
 * - **fingerprint mismatch** — the request's fingerprint differs from any stored artifact's: derive
 *   fresh for the requested fingerprint rather than serving the stored one.
 * - **unreadable or truncated** — a stored artifact that is missing, unparseable, or whose payload
 *   does not match its recorded size falls through to derivation. A corrupt cache entry never fails
 *   the run.
 * - **fingerprint contradiction** — a well-formed stored envelope whose recorded fingerprint
 *   disagrees with the address it was stored under is not a corrupt read but a broken store
 *   invariant: it loud-fails with
 *   [FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError] naming both fingerprints,
 *   rather than being served. This branch is unreachable from the truncation branch: input that
 *   cannot be parsed re-derives and never reaches the contradiction check.
 *
 * Resolution is scoped per workflow; there is no cross-run or global key, and fingerprint equality
 * is the only invalidation concept.
 */
fun interface FeatureTaskRuntimeSharedEvidenceResolverPort {
  fun resolve(
    request: FeatureTaskRuntimeSharedEvidenceRequest,
    deriver: FeatureTaskRuntimeSharedEvidenceDeriver,
  ): FeatureTaskRuntimeSharedEvidenceArtifact
}

/**
 * Address of one resolution: [repoRoot] anchors the repo-local store, and [workflowId] plus the
 * [checkpoint] fingerprint together address the artifact within it.
 */
data class FeatureTaskRuntimeSharedEvidenceRequest(
  val repoRoot: Path,
  val workflowId: String,
  val checkpoint: FeatureTaskRuntimeRepositoryCheckpoint,
) {
  init {
    require(workflowId.isNotBlank()) {
      "FeatureTaskRuntimeSharedEvidenceRequest.workflowId must be non-blank; an unaddressed " +
        "resolution would share one cache slot across every workflow."
    }
  }
}

/**
 * A stored artifact recorded one fingerprint but lives at the address of another. Serving it would
 * hand a phase evidence for the wrong repository state, so the store fails loudly instead.
 */
class FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError(
  val addressedFingerprint: String,
  val recordedFingerprint: String,
  val sourceLabel: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Shared review evidence at '$sourceLabel' is addressed by fingerprint '$addressedFingerprint' but " +
    "records fingerprint '$recordedFingerprint'; refusing to serve evidence for a contradicted checkpoint.",
  cause,
)

package skillbill.ports.taskruntime

import skillbill.error.ShellContentContractException
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceRequest
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolution
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceDiffPayloadRef

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
  ): FeatureTaskRuntimeSharedEvidenceResolution

  companion object {
    /**
     * No store at all: every resolution derives in line and nothing is persisted. This is the same
     * path a cache miss takes, so a caller wired to [NONE] behaves exactly as it did before any
     * store existed.
     */
    val NONE: FeatureTaskRuntimeSharedEvidenceResolverPort =
      FeatureTaskRuntimeSharedEvidenceResolverPort { request, deriver ->
        val derivation = deriver.derive(request.checkpoint)
        FeatureTaskRuntimeSharedEvidenceResolution(
          artifact = FeatureTaskRuntimeSharedEvidenceArtifact(
            fingerprint = request.checkpoint.fingerprint,
            baseRef = derivation.baseRef,
            headRef = derivation.headRef,
            files = derivation.files,
            hunks = derivation.hunks,
            diffPayload = FeatureTaskRuntimeSharedEvidenceDiffPayloadRef(
              relativePath = UNPERSISTED_PAYLOAD_NAME,
              sizeBytes = derivation.diffPayload.toByteArray().size.toLong(),
            ),
          ),
          diffPayload = derivation.diffPayload,
        )
      }

    /** Names a payload that was never written; [NONE] persists nothing, so nothing dereferences it. */
    private const val UNPERSISTED_PAYLOAD_NAME = "unpersisted.patch"
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

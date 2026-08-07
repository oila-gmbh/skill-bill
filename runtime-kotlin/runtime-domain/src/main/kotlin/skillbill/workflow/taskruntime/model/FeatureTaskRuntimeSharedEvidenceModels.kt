package skillbill.workflow.taskruntime.model

/**
 * Phase-neutral derived review evidence for one repository checkpoint.
 *
 * [fingerprint] is the checkpoint digest the artifact was derived against and the only
 * invalidation key: an artifact is reusable exactly when the requesting checkpoint's fingerprint
 * equals this one. [baseRef] and [headRef] carry the resolved pair the derivation actually used,
 * so a consumer never has to re-resolve them.
 *
 * [diffPayload] is a reference, never the bytes: the materialized diff can be arbitrarily large
 * and a domain value that inlined it would push that weight through every projection that carries
 * the artifact.
 */
data class FeatureTaskRuntimeSharedEvidenceArtifact(
  val fingerprint: String,
  val baseRef: String?,
  val headRef: String?,
  val files: List<FeatureTaskRuntimeSharedEvidenceFileEntry>,
  val hunks: List<FeatureTaskRuntimeSharedEvidenceHunkEntry>,
  val diffPayload: FeatureTaskRuntimeSharedEvidenceDiffPayloadRef,
) {
  init {
    require(fingerprint.isNotBlank()) {
      "FeatureTaskRuntimeSharedEvidenceArtifact.fingerprint must be non-blank; evidence that cannot " +
        "name the checkpoint it was derived against can never be safely reused."
    }
  }
}

/** One changed path in the artifact's file index. */
data class FeatureTaskRuntimeSharedEvidenceFileEntry(
  val path: String,
  val changeKind: String,
) {
  init {
    require(path.isNotBlank()) {
      "FeatureTaskRuntimeSharedEvidenceFileEntry.path must be non-blank."
    }
    require(changeKind.isNotBlank()) {
      "FeatureTaskRuntimeSharedEvidenceFileEntry.changeKind must be non-blank."
    }
  }
}

/** One hunk of the materialized diff, addressed by path and hunk header. */
data class FeatureTaskRuntimeSharedEvidenceHunkEntry(
  val path: String,
  val header: String,
) {
  init {
    require(path.isNotBlank()) {
      "FeatureTaskRuntimeSharedEvidenceHunkEntry.path must be non-blank."
    }
    require(header.isNotBlank()) {
      "FeatureTaskRuntimeSharedEvidenceHunkEntry.header must be non-blank."
    }
  }
}

/**
 * Reference to the materialized diff payload. [relativePath] is relative to the artifact's own
 * store directory, so the reference stays valid however the store root is resolved. [sizeBytes]
 * is what a reader compares the stored payload against to detect truncation.
 */
data class FeatureTaskRuntimeSharedEvidenceDiffPayloadRef(
  val relativePath: String,
  val sizeBytes: Long,
) {
  init {
    require(relativePath.isNotBlank()) {
      "FeatureTaskRuntimeSharedEvidenceDiffPayloadRef.relativePath must be non-blank."
    }
    require(sizeBytes >= 0) {
      "FeatureTaskRuntimeSharedEvidenceDiffPayloadRef.sizeBytes must not be negative, was $sizeBytes."
    }
  }
}

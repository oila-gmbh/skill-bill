@file:Suppress("MaxLineLength")

package skillbill.workflow.taskruntime.model

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION

object FeatureTaskRuntimePlanningProjectionContract {
  const val SHARED_REVIEW_EVIDENCE_ID: String = "feature_task_runtime.shared_review_evidence"
  val VERSION: String = FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION

  private val producedProjectionKindsByPhaseId: Map<String, String> = emptyMap()

  fun producedProjectionKindFor(phaseId: String): String? = producedProjectionKindsByPhaseId[phaseId]
}

const val FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT: Int = 128

const val FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT: Int = 512

fun featureTaskRuntimeRenderOpenWorkItem(value: Any?): String? = when (value) {
  null -> null
  is String -> value.trim().takeIf(String::isNotBlank)
  is Map<*, *> -> {
    val ref = (value["ref"] as? String)?.trim().orEmpty()
    val note = (value["note"] as? String)?.trim().orEmpty()
    when {
      ref.isNotBlank() && note.isNotBlank() -> "$ref: $note"
      ref.isNotBlank() -> ref
      note.isNotBlank() -> note
      else -> value.toString().trim().takeIf(String::isNotBlank)
    }
  }
  else -> value.toString().trim().takeIf(String::isNotBlank)
}

data class FeatureTaskRuntimeSharedReviewEvidenceReference(
  val storePath: String,
  val checkpointFingerprint: String,
  val baseRef: String?,
  val headRef: String?,
  val fileHunkIndex: List<String>,
) {
  init {
    require(storePath.isNotBlank()) {
      "FeatureTaskRuntimeSharedReviewEvidenceReference.storePath must be non-blank; a reference that " +
        "cannot name its artifact is not dereferenceable."
    }
    require(checkpointFingerprint.isNotBlank()) {
      "FeatureTaskRuntimeSharedReviewEvidenceReference.checkpointFingerprint must be non-blank; the " +
        "fingerprint is the artifact's only reuse key."
    }
    require(fileHunkIndex.size <= FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT) {
      "FeatureTaskRuntimeSharedReviewEvidenceReference.fileHunkIndex allows at most " +
        "$FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT entries, had ${fileHunkIndex.size}."
    }
  }

  fun toProjectionFields(): List<FeatureTaskRuntimeHandoffProjectionField> = listOfNotNull(
    FeatureTaskRuntimeHandoffProjectionField(
      name = FIELD_STORE_PATH,
      value = FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
        kind = FeatureTaskRuntimeCompactReferenceKind.PRIVATE_EVIDENCE_ARTIFACT,
        value = storePath,
      ),
    ),
    FeatureTaskRuntimeHandoffProjectionField(
      name = FIELD_CHECKPOINT_FINGERPRINT,
      value = FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
        kind = FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT,
        value = checkpointFingerprint,
      ),
    ),
    baseRef?.let {
      FeatureTaskRuntimeHandoffProjectionField(FIELD_BASE_REF, FeatureTaskRuntimeHandoffProjectionValue.Text(it))
    },
    headRef?.let {
      FeatureTaskRuntimeHandoffProjectionField(FIELD_HEAD_REF, FeatureTaskRuntimeHandoffProjectionValue.Text(it))
    },
    FeatureTaskRuntimeHandoffProjectionField(
      name = FIELD_FILE_HUNK_INDEX,
      value = FeatureTaskRuntimeHandoffProjectionValue.TextList(fileHunkIndex),
    ),
  )

  companion object {
    const val FIELD_STORE_PATH: String = "store_path"
    const val FIELD_CHECKPOINT_FINGERPRINT: String = "checkpoint_fingerprint"
    const val FIELD_BASE_REF: String = "base_ref"
    const val FIELD_HEAD_REF: String = "head_ref"
    const val FIELD_FILE_HUNK_INDEX: String = "file_hunk_index"

    val DECLARED_FIELD_NAMES: List<String> = listOf(
      FIELD_STORE_PATH,
      FIELD_CHECKPOINT_FINGERPRINT,
      FIELD_BASE_REF,
      FIELD_HEAD_REF,
      FIELD_FILE_HUNK_INDEX,
    )

    fun of(
      storePath: String,
      artifact: FeatureTaskRuntimeSharedEvidenceArtifact,
    ): FeatureTaskRuntimeSharedReviewEvidenceReference {
      val hunkCounts = artifact.hunks.groupingBy { it.path }.eachCount()
      val entries = artifact.files.map { file ->
        "${file.changeKind} ${file.path} hunks=${hunkCounts[file.path] ?: 0}"
      }
      val bounded = if (entries.size <= FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT) {
        entries
      } else {
        entries.take(FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT - 1) +
          "omitted ${entries.size - (FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT - 1)} further changed files"
      }
      return FeatureTaskRuntimeSharedReviewEvidenceReference(
        storePath = storePath,
        checkpointFingerprint = artifact.fingerprint,
        baseRef = artifact.baseRef?.takeIf(String::isNotBlank),
        headRef = artifact.headRef?.takeIf(String::isNotBlank),
        fileHunkIndex = bounded,
      )
    }
  }
}

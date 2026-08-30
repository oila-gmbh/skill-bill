package skillbill.workflow.taskruntime.model

internal object FeatureTaskRuntimeProjectionCanonicalizerTopLevel {
  fun canonicalizeTopLevelKey(
    key: String,
    value: Any?,
    declaredIds: Map<String, String>,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
  ): Any? = when (key) {
    "tasks" -> FeatureTaskRuntimeProjectionCanonicalizerMapOps.mapEntries(value) { index, entry ->
      FeatureTaskRuntimeProjectionCanonicalizerEntries.canonicalizeTaskEntry(entry, declaredIds, records, index)
    }
    "task_commitments" -> FeatureTaskRuntimeProjectionCanonicalizerMapOps.mapEntries(value) { index, entry ->
      FeatureTaskRuntimeProjectionCanonicalizerEntries.canonicalizeCommitmentEntry(entry, records, index)
    }
    "deviations" -> FeatureTaskRuntimeProjectionCanonicalizerMapOps.mapEntries(value) { index, entry ->
      FeatureTaskRuntimeProjectionCanonicalizerEntries.canonicalizeDeviationEntry(entry, records, index)
    }
    "completed_task_ids" ->
      FeatureTaskRuntimeProjectionCanonicalizerEntries.canonicalizeReferenceIds(value, declaredIds, records, key)
    "tests_executed" -> FeatureTaskRuntimeProjectionCanonicalizerMutations.discardUnknownKeysInEntries(
      value,
      FEATURE_TASK_RUNTIME_TEST_EXECUTION_KEYS,
      key,
      records,
    )
    "reconciliation_evidence" -> canonicalizeReconciliationEvidence(value, records)
    "repository_checkpoint" -> canonicalizeRepositoryCheckpoint(value, records, key)
    in FEATURE_TASK_RUNTIME_NONBLANK_STRING_LIST_KEYS ->
      FeatureTaskRuntimeProjectionCanonicalizerEntries.trimStringList(value, records, key)
    else -> value
  }

  private fun canonicalizeReconciliationEvidence(
    value: Any?,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
  ): Any? = FeatureTaskRuntimeProjectionCanonicalizerMapOps.mapObject(
    FeatureTaskRuntimeProjectionCanonicalizerMutations.promotedReconciliationEvidence(value, records),
  ) {
    FeatureTaskRuntimeProjectionCanonicalizerMutations.trimNonBlank(
      FeatureTaskRuntimeProjectionCanonicalizerMutations.discardUnknownKeys(
        FeatureTaskRuntimeProjectionCanonicalizerMutations.adoptedProseKey(
          it,
          FEATURE_TASK_RUNTIME_RECONCILIATION_EVIDENCE_KEYS,
          "evidence",
          "reconciliation_evidence",
          records,
        ),
        FEATURE_TASK_RUNTIME_RECONCILIATION_EVIDENCE_KEYS,
        "reconciliation_evidence",
        records,
      ),
      "evidence",
      records,
      "reconciliation_evidence.evidence",
    )
  }

  private fun canonicalizeRepositoryCheckpoint(
    value: Any?,
    records: MutableList<FeatureTaskRuntimeProjectionCanonicalizationRecord>,
    key: String,
  ): Any? = FeatureTaskRuntimeProjectionCanonicalizerMapOps.mapObject(value) { checkpoint ->
    FeatureTaskRuntimeProjectionCanonicalizerMutations.trimNonBlank(
      FeatureTaskRuntimeProjectionCanonicalizerMutations.trimNonBlank(
        FeatureTaskRuntimeProjectionCanonicalizerMutations.trimNonBlank(
          FeatureTaskRuntimeProjectionCanonicalizerMutations.discardUnknownKeys(
            checkpoint,
            FEATURE_TASK_RUNTIME_REPOSITORY_CHECKPOINT_KEYS,
            key,
            records,
          ),
          "fingerprint",
          records,
          "repository_checkpoint.fingerprint",
        ),
        "base_ref",
        records,
        "repository_checkpoint.base_ref",
      ),
      "head_ref",
      records,
      "repository_checkpoint.head_ref",
    )
  }
}

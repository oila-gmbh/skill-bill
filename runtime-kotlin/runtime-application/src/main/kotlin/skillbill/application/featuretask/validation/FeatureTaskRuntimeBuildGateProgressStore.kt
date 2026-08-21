package skillbill.application.featuretask.validation

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress

class FeatureTaskRuntimeBuildGateProgressStore private constructor(
  private val recorder: FeatureTaskRuntimePhaseRecorder?,
  private val persistOverride: ((String, FeatureTaskRuntimeValidationGateProgress, String?) -> Unit)?,
  private val loadOverride: ((String, String?) -> FeatureTaskRuntimeValidationGateProgress?)?,
) {
  @Inject
  constructor(recorder: FeatureTaskRuntimePhaseRecorder) : this(recorder, null, null)

  internal constructor(
    persist: (String, FeatureTaskRuntimeValidationGateProgress, String?) -> Unit,
    load: (String, String?) -> FeatureTaskRuntimeValidationGateProgress?,
  ) : this(null, persist, load)

  fun persist(workflowId: String, progress: FeatureTaskRuntimeValidationGateProgress, dbOverride: String?) {
    when {
      persistOverride != null -> persistOverride.invoke(workflowId, progress, dbOverride)
      recorder != null -> recorder.persistBuildGateProgress(workflowId, progress, dbOverride)
      else -> error("FeatureTaskRuntimeBuildGateProgressStore has no backing store.")
    }
  }

  fun load(workflowId: String, dbOverride: String?): FeatureTaskRuntimeValidationGateProgress? = when {
    loadOverride != null -> loadOverride.invoke(workflowId, dbOverride)
    recorder != null -> recorder.loadBuildGateProgress(workflowId, dbOverride)
    else -> error("FeatureTaskRuntimeBuildGateProgressStore has no backing store.")
  }
}

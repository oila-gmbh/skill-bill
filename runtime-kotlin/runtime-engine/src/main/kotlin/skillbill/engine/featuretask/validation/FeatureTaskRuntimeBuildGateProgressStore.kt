package skillbill.engine.featuretask.validation

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress

class FeatureTaskRuntimeBuildGateProgressStore private constructor(
  private val recorder: FeatureTaskRuntimePhaseRecorder?,
  private val persistOverride: ((String, FeatureTaskRuntimeValidationGateProgress) -> Unit)?,
  private val loadOverride: ((String) -> FeatureTaskRuntimeValidationGateProgress?)?,
) {
  @Inject
  constructor(recorder: FeatureTaskRuntimePhaseRecorder) : this(recorder, null, null)

  internal constructor(
    persist: (String, FeatureTaskRuntimeValidationGateProgress) -> Unit,
    load: (String) -> FeatureTaskRuntimeValidationGateProgress?,
  ) : this(null, persist, load)

  fun persist(workflowId: String, progress: FeatureTaskRuntimeValidationGateProgress) {
    when {
      persistOverride != null -> persistOverride.invoke(workflowId, progress)
      recorder != null -> recorder.persistBuildGateProgress(workflowId, progress)
      else -> error("FeatureTaskRuntimeBuildGateProgressStore has no backing store.")
    }
  }

  fun load(workflowId: String): FeatureTaskRuntimeValidationGateProgress? = when {
    loadOverride != null -> loadOverride.invoke(workflowId)
    recorder != null -> recorder.loadBuildGateProgress(workflowId)
    else -> error("FeatureTaskRuntimeBuildGateProgressStore has no backing store.")
  }
}

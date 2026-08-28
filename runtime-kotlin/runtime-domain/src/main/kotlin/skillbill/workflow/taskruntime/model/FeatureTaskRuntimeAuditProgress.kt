package skillbill.workflow.taskruntime.model

data class FeatureTaskRuntimeAuditProgress(
  val firstPassConvergence: Boolean,
  val auditGapIterationCount: Int,
)

const val FEATURE_TASK_RUNTIME_AUDIT_NOTE_MAX_CHARS: Int = 1024

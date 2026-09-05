package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidFeatureTaskRuntimePhaseHandoffSchemaError

internal fun unrecognizedHandoffWireValue(field: String, value: String): Nothing =
  throw InvalidFeatureTaskRuntimePhaseHandoffSchemaError(
    sourceLabel = "<wire>",
    reason = "Unrecognized feature-task-runtime handoff $field wire value '$value'.",
  )

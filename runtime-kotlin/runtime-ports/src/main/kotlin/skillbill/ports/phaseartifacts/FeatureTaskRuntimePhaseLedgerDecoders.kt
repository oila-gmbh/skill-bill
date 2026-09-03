package skillbill.ports.phaseartifacts
import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry

fun decomposeTerminalFrom(artifacts: Map<String, Any?>): FeatureTaskRuntimeDecomposeTerminal? {
  val raw = artifacts[FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY] ?: return null
  val entryMap = JsonSupport.anyToStringAnyMap(raw)
    ?: schemaError(
      "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY' must decode to a map.",
    )
  return FeatureTaskRuntimeDecomposeTerminal.fromArtifactMap(entryMap)
}

@OpenBoundaryMap("Feature-task-runtime phase ledger decode from durable workflow artifacts")
fun phaseLedgerFrom(artifacts: Map<String, Any?>): List<FeatureTaskRuntimePhaseLedgerEntry> {
  val raw = artifacts[FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY] ?: return emptyList()
  val rawList = raw as? List<*>
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY' must decode to a list.",
    )
  return rawList.map { item ->
    val entryMap = JsonSupport.anyToStringAnyMap(item)
      ?: throw InvalidWorkflowStateSchemaError(
        "Feature-task-runtime phase ledger entry must decode to a string-keyed map.",
      )
    FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap(entryMap)
  }
}

package skillbill.workflow

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.model.WorkflowDefinition
import skillbill.workflow.model.WorkflowInputProjection
import skillbill.workflow.model.WorkflowSnapshotView

/**
 * The single selection boundary used by fresh launches and continuations. It never falls back
 * to the complete durable artifact map and it rejects, rather than truncates, oversized input.
 */
object WorkflowInputProjectionSelector {
  fun select(
    definition: WorkflowDefinition,
    snapshot: WorkflowSnapshotView,
    stepId: String,
    producerIteration: Int,
  ): WorkflowInputProjection {
    val declaration = definition.inputProjectionsByStep[stepId]
      ?: throw invalid(definition, "missing input projection for step '$stepId'")
    val missing = declaration.requiredArtifactKeys.filterNot(snapshot.artifacts::containsKey)
    if (missing.isNotEmpty()) {
      throw invalid(definition, "projection for step '$stepId' is missing required artifact keys: ${missing.joinToString()}")
    }
    val selected = declaration.requiredArtifactKeys.associateWith(snapshot.artifacts::get)
    val forbidden = selected.keys.intersect(declaration.forbiddenArtifactKeys)
    if (forbidden.isNotEmpty()) {
      throw invalid(definition, "projection for step '$stepId' contains forbidden artifact keys: ${forbidden.sorted().joinToString()}")
    }
    val itemCount = selected.values.sumOf(::collectionItemCount)
    if (itemCount > declaration.maxCollectionItems) {
      throw invalid(definition, "projection for step '$stepId' exceeds its collection-item budget")
    }
    val bytes = JsonSupport.json.encodeToString(
      kotlinx.serialization.json.JsonObject.serializer(),
      JsonSupport.mapToJsonObject(selected),
    ).toByteArray(Charsets.UTF_8).size
    if (bytes > declaration.maxUtf8Bytes) {
      throw invalid(definition, "projection for step '$stepId' exceeds its UTF-8 byte budget")
    }
    return WorkflowInputProjection(
      stepId = stepId,
      producerIteration = producerIteration,
      repositoryCheckpoint = snapshot.artifacts[declaration.repositoryCheckpointArtifactKey],
      artifacts = selected,
      utf8Bytes = bytes,
    )
  }

  private fun collectionItemCount(value: Any?): Int = when (value) {
    is Map<*, *> -> value.size + value.values.sumOf(::collectionItemCount)
    is Iterable<*> -> value.sumOf(::collectionItemCount)
    is Array<*> -> value.sumOf(::collectionItemCount)
    else -> 1
  }

  private fun invalid(definition: WorkflowDefinition, detail: String) =
    InvalidWorkflowStateSchemaError("${definition.workflowName}: $detail")
}

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
    resolvedRepositoryCheckpointIdentity: String,
  ): WorkflowInputProjection {
    if (resolvedRepositoryCheckpointIdentity.isBlank()) {
      throw invalid(definition, "projection for step '$stepId' has no runtime-resolved repository checkpoint")
    }
    val declaration = definition.inputProjectionsByStep[stepId]
      ?: throw invalid(definition, "missing input projection for step '$stepId'")
    val missing = declaration.requiredArtifactKeys.filterNot(snapshot.artifacts::containsKey)
    if (missing.isNotEmpty()) {
      throw invalid(definition, "projection for step '$stepId' is missing required artifact keys: ${missing.joinToString()}")
    }
    val selected = declaration.requiredArtifactKeys.associateWith { artifactKey ->
      projectArtifact(
        definition = definition,
        stepId = stepId,
        artifactKey = artifactKey,
        value = snapshot.artifacts[artifactKey],
        projectedFields = declaration.projectedFieldsByArtifactKey[artifactKey],
      )
    }
    val forbidden = selected.keys.intersect(declaration.forbiddenArtifactKeys) +
      selected.values.flatMapTo(mutableSetOf()) { nestedKeys(it) }.intersect(declaration.forbiddenArtifactKeys)
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
    val repositoryCheckpoint = selected[declaration.repositoryCheckpointArtifactKey]
      ?: throw invalid(definition, "projection for step '$stepId' has null repository checkpoint evidence")
    val checkpoint = repositoryCheckpoint as? Map<*, *>
      ?: throw invalid(definition, "projection for step '$stepId' repository checkpoint evidence is not typed")
    val checkpointIdentity = checkpoint["fingerprint"] ?: checkpoint["checkpoint"]
      ?: throw invalid(definition, "projection for step '$stepId' repository checkpoint evidence has no identity")
    if (checkpointIdentity != resolvedRepositoryCheckpointIdentity) {
      throw invalid(
        definition,
        "projection for step '$stepId' repository checkpoint evidence does not match the runtime-resolved checkpoint",
      )
    }
    val claimedIdentity = checkpoint["repository_checkpoint"] ?: checkpoint["checkpoint"] ?: checkpoint["fingerprint"]
    if (claimedIdentity != checkpointIdentity) {
      throw invalid(definition, "projection for step '$stepId' repository checkpoint evidence is stale or mismatched")
    }
    selected
      .filterKeys { it != declaration.repositoryCheckpointArtifactKey }
      .values
      .mapNotNull(::nestedRepositoryCheckpointIdentity)
      .firstOrNull { it != checkpointIdentity }
      ?.let {
        throw invalid(
          definition,
          "projection for step '$stepId' artifact checkpoint does not match authoritative repository evidence",
        )
      }
    return WorkflowInputProjection(
      stepId = stepId,
      producerIteration = producerIteration,
      repositoryCheckpoint = repositoryCheckpoint,
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

  private fun projectArtifact(
    definition: WorkflowDefinition,
    stepId: String,
    artifactKey: String,
    value: Any?,
    projectedFields: Set<String>?,
  ): Any? {
    if (projectedFields == null) return value
    val typed = value as? Map<*, *>
      ?: throw invalid(definition, "projection for step '$stepId' artifact '$artifactKey' is not typed")
    val missingFields = projectedFields.filterNot(typed::containsKey)
    if (missingFields.isNotEmpty()) {
      throw invalid(
        definition,
        "projection for step '$stepId' artifact '$artifactKey' is missing required fields: " +
          missingFields.sorted().joinToString(),
      )
    }
    return projectedFields.associateWith(typed::get)
  }

  private fun nestedKeys(value: Any?): Set<String> = when (value) {
    is Map<*, *> -> value.entries.flatMapTo(mutableSetOf()) { (key, nested) ->
      buildSet {
        if (key is String) add(key)
        addAll(nestedKeys(nested))
      }
    }
    is Iterable<*> -> value.flatMapTo(mutableSetOf(), ::nestedKeys)
    is Array<*> -> value.flatMapTo(mutableSetOf(), ::nestedKeys)
    else -> emptySet()
  }

  private fun nestedRepositoryCheckpointIdentity(value: Any?): Any? {
    val typed = value as? Map<*, *> ?: return null
    val nested = typed["repository_checkpoint"] as? Map<*, *> ?: return null
    return nested["fingerprint"] ?: nested["checkpoint"]
  }

  private fun invalid(definition: WorkflowDefinition, detail: String) =
    InvalidWorkflowStateSchemaError("${definition.workflowName}: $detail")
}

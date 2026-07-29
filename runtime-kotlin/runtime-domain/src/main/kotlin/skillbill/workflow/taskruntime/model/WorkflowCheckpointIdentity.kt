package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap

data class WorkflowCheckpointIdentity(
  val branch: String,
  val phase: String,
  val loop: String,
  val generation: Int,
  val parentSha: String,
  val ownedPathDigest: String,
  val ownedPaths: List<String>,
  val commitSha: String,
) {
  init {
    require(branch.isNotBlank())
    require(phase.isNotBlank())
    require(loop.isNotBlank())
    require(generation > 0)
    require(CHECKPOINT_SHA.matches(parentSha))
    require(CHECKPOINT_DIGEST.matches(ownedPathDigest))
    require(ownedPaths.isNotEmpty() && ownedPaths.all(String::isNotBlank))
    require(CHECKPOINT_SHA.matches(commitSha))
  }

  @OpenBoundaryMap("Checkpoint identity projection at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "branch" to branch,
    "phase" to phase,
    "loop" to loop,
    "generation" to generation,
    "parent_sha" to parentSha,
    "owned_path_digest" to ownedPathDigest,
    "owned_paths" to ownedPaths,
    "commit_sha" to commitSha,
  )

  companion object {
    @OpenBoundaryMap("Checkpoint identity decode from the durable workflow-artifact seam")
    fun fromArtifactMap(raw: Map<String, Any?>): WorkflowCheckpointIdentity = WorkflowCheckpointIdentity(
      branch = raw.requireCheckpointString("branch"),
      phase = raw.requireCheckpointString("phase"),
      loop = raw.requireCheckpointString("loop"),
      generation = (raw["generation"] as? Number)?.toInt()
        ?: error("Checkpoint identity generation must be an integer."),
      parentSha = raw.requireCheckpointString("parent_sha"),
      ownedPathDigest = raw.requireCheckpointString("owned_path_digest"),
      ownedPaths = (raw["owned_paths"] as? List<*>)
        ?.map { it as? String ?: error("Checkpoint identity owned_paths must contain only strings.") }
        ?.takeIf { it.isNotEmpty() }
        ?: error("Checkpoint identity owned_paths must be a non-empty list."),
      commitSha = raw.requireCheckpointString("commit_sha"),
    )
  }
}

private val CHECKPOINT_SHA = Regex("^[0-9a-f]{40}(?:[0-9a-f]{24})?$")
private val CHECKPOINT_DIGEST = Regex("^[0-9a-f]{64}$")

private fun Map<String, Any?>.requireCheckpointString(field: String): String =
  (this[field] as? String)?.takeIf(String::isNotBlank)
    ?: error("Checkpoint identity $field must be a non-blank string.")

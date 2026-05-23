@file:Suppress("LongParameterList")

package skillbill.application

import skillbill.application.model.DecompositionManifestRuntimeUpdate
import skillbill.application.model.DecompositionManifestWriteRequest
import skillbill.application.model.DecompositionManifestWriteResult
import skillbill.workflow.DecompositionManifestCodec
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.writeDecompositionManifestText
import java.io.IOException
import java.nio.file.Path

private const val DECOMPOSITION_MODE: String = "decompose"
private const val DECOMPOSITION_MANIFEST_FILENAME: String = "decomposition-manifest.yaml"
internal const val DECOMPOSITION_RUNTIME_ARTIFACT_KEY: String = "decomposition_runtime"

object DecompositionManifestWriter {
  fun writeFromWorkflowUpdate(
    repoRoot: Path,
    existingArtifactsJson: String,
    artifactsPatch: Map<String, Any?>?,
    workflowId: String = "",
    workflowStatus: String = "",
    currentStepId: String = "",
    stepUpdates: List<Map<String, Any?>>? = null,
    runtimeUpdate: DecompositionManifestRuntimeUpdate? = null,
  ): DecompositionManifestWriteResult? {
    val manifest = manifestFromWorkflowUpdate(
      repoRoot = repoRoot,
      existingArtifactsJson = existingArtifactsJson,
      artifactsPatch = artifactsPatch,
      workflowId = workflowId,
      workflowStatus = workflowStatus,
      currentStepId = currentStepId,
      stepUpdates = stepUpdates,
      runtimeUpdate = runtimeUpdate,
    ) ?: return null
    return writeProjection(repoRoot, manifest)
  }

  fun manifestFromWorkflowUpdate(
    repoRoot: Path,
    existingArtifactsJson: String,
    artifactsPatch: Map<String, Any?>?,
    workflowId: String = "",
    workflowStatus: String = "",
    currentStepId: String = "",
    stepUpdates: List<Map<String, Any?>>? = null,
    runtimeUpdate: DecompositionManifestRuntimeUpdate? = null,
  ): DecompositionManifest? {
    val existingArtifacts = decodeArtifacts(existingArtifactsJson)
    val update = (
      runtimeUpdate ?: DecompositionManifestRuntimeUpdate(
        workflowId = workflowId,
        workflowStatus = workflowStatus,
        currentStepId = currentStepId,
        stepUpdates = stepUpdates,
      )
      ).copy(
      artifactsPatch = artifactsPatch,
      existingArtifacts = existingArtifacts,
    )
    val plan = artifactsPatch?.get("plan").asStringAnyMapOrNull()
    return if (plan != null && plan["mode"] == DECOMPOSITION_MODE) {
      manifestFromDecompositionPlan(repoRoot, plan, artifactsPatch, existingArtifacts)
    } else {
      updatedExistingManifest(repoRoot, update)
    }
  }

  fun maybeWriteFromWorkflowUpdate(
    repoRoot: Path,
    existingArtifactsJson: String,
    artifactsPatch: Map<String, Any?>?,
  ): Path? = writeFromWorkflowUpdate(repoRoot, existingArtifactsJson, artifactsPatch)?.manifestPath

  fun writeProjectionFromWorkflowState(repoRoot: Path, artifactsJson: String): DecompositionManifestWriteResult? {
    val artifacts = decodeArtifacts(artifactsJson)
    val runtime = artifacts[DECOMPOSITION_RUNTIME_ARTIFACT_KEY].asStringAnyMapOrNull()
      ?.let { DecompositionManifestCodec.decodeMap(it, DECOMPOSITION_RUNTIME_ARTIFACT_KEY) }
      ?: return null
    val manifestPath = resolvedParentSpecPath(repoRoot, Path.of(runtime.parentSpecPath))
      .parent
      .resolve(DECOMPOSITION_MANIFEST_FILENAME)
    return try {
      writeProjection(repoRoot, runtime, manifestPath)
    } catch (_: IOException) {
      null
    }
  }

  fun writeIfDecomposed(request: DecompositionManifestWriteRequest): DecompositionManifestWriteResult? {
    if (request.planningResult["mode"]?.toString().orEmpty() != DECOMPOSITION_MODE) {
      return null
    }
    return write(request)
  }

  fun write(
    request: DecompositionManifestWriteRequest,
    runtimeUpdate: DecompositionManifestRuntimeUpdate? = null,
  ): DecompositionManifestWriteResult {
    val manifestPath = resolvedParentSpecPath(request.repoRoot, request.parentSpecPath)
      .parent
      .resolve(DECOMPOSITION_MANIFEST_FILENAME)
    val existing = loadManifestOrNull(manifestPath)
    val manifest = request.toManifest()
      .assertExecutionModelCanReplace(existing, manifestPath)
      .withPreservedRuntimeState(existing)
      .let { candidate ->
        runtimeUpdate?.let { candidate.withRuntimeUpdate(request.repoRoot, it) } ?: candidate
      }
    val yaml = DecompositionManifestCodec.encodeYaml(manifest)
    writeDecompositionManifestText(manifestPath, yaml)
    val loaded = DecompositionManifestCodec.load(manifestPath)
    projectCurrentSubtaskStatus(request.repoRoot, loaded)
    return DecompositionManifestWriteResult(manifestPath = manifestPath, manifest = loaded)
  }

  private fun manifestFromDecompositionPlan(
    repoRoot: Path,
    plan: Map<String, Any?>,
    artifactsPatch: Map<String, Any?>?,
    existingArtifacts: Map<String, Any?>,
  ): DecompositionManifest {
    val parentSpecPath = Path.of(parentSpecPath(plan))
    val branchName = branchName(artifactsPatch?.get("branch")).ifBlank { branchName(existingArtifacts["branch"]) }
    val executionModel = executionModel(plan)
    val request = DecompositionManifestWriteRequest(
      repoRoot = repoRoot,
      parentSpecPath = parentSpecPath,
      planningResult = plan,
      baseBranch = plan["base_branch"]?.toString()?.takeIf(String::isNotBlank) ?: "main",
      featureBranch = when (executionModel) {
        DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK ->
          branchName.ifBlank { defaultFeatureBranch(parentSpecPath) }
        DecompositionExecutionModel.STACKED_BRANCHES -> null
      },
      executionModel = executionModel,
      stackBranches = parseStackBranches(plan),
    )
    val manifestPath = request.manifestPath()
    val existing = runtimeManifestFromArtifacts(existingArtifacts) ?: loadManifestOrNull(manifestPath)
    return request.toManifest()
      .assertExecutionModelCanReplace(existing, manifestPath)
      .withPreservedRuntimeState(existing)
  }

  private fun updatedExistingManifest(
    repoRoot: Path,
    runtimeUpdate: DecompositionManifestRuntimeUpdate,
  ): DecompositionManifest? {
    val artifacts = LinkedHashMap(runtimeUpdate.existingArtifacts).apply {
      runtimeUpdate.artifactsPatch?.let(::putAll)
    }
    val runtime = runtimeManifestFromArtifacts(artifacts)
    val manifestPath = manifestPathFromArtifacts(
      repoRoot = repoRoot,
      artifactsPatch = runtimeUpdate.artifactsPatch,
      existingArtifacts = runtimeUpdate.existingArtifacts,
    ) ?: runtime?.manifestPath(repoRoot)
    val existing = runtime ?: manifestPath?.let(::loadManifestOrNull) ?: return null
    return existing.withRuntimeUpdate(repoRoot, runtimeUpdate)
  }

  private fun DecompositionManifestWriteRequest.toManifest(): DecompositionManifest {
    val subtasks = parseSubtasks(planningResult, parentSpecPath.toString())
    val currentId = currentSubtaskId
      ?: planningResult.intValueOrNull("current_subtask_id")
      ?: planningResult.intValueOrNull("recommended_first_subtask_id")
      ?: subtasks.first().id
    val currentSubtask = subtasks.firstOrNull { it.id == currentId }
      ?: invalidManifest(
        parentSpecPath.toString(),
        "current subtask id '$currentId' does not reference a planned subtask.",
      )
    val (issueKey, featureName) = issueAndFeature(
      resolvedParentSpecPath(repoRoot, parentSpecPath).parent.fileName.toString(),
    )
    return DecompositionManifest(
      issueKey = issueKey,
      featureName = featureName,
      parentSpecPath = repoRelativePath(repoRoot, parentSpecPath),
      executionModel = executionModel,
      baseBranch = baseBranch,
      featureBranch = featureBranch,
      stackBranches = stackBranches,
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = currentSubtask.id, action = "start"),
      subtasks = subtasks,
    )
  }
}

private fun DecompositionManifestWriteRequest.manifestPath(): Path =
  resolvedParentSpecPath(repoRoot, parentSpecPath).parent.resolve(DECOMPOSITION_MANIFEST_FILENAME)

private fun DecompositionManifest.manifestPath(repoRoot: Path): Path =
  resolvedParentSpecPath(repoRoot, Path.of(parentSpecPath)).parent.resolve(DECOMPOSITION_MANIFEST_FILENAME)

private fun runtimeManifestFromArtifacts(artifacts: Map<String, Any?>): DecompositionManifest? =
  artifacts[DECOMPOSITION_RUNTIME_ARTIFACT_KEY].asStringAnyMapOrNull()
    ?.let { DecompositionManifestCodec.decodeMap(it, DECOMPOSITION_RUNTIME_ARTIFACT_KEY) }

private fun writeProjection(
  repoRoot: Path,
  manifest: DecompositionManifest,
  manifestPath: Path = manifest.manifestPath(repoRoot),
): DecompositionManifestWriteResult? = try {
  val yaml = DecompositionManifestCodec.encodeYaml(manifest)
  writeDecompositionManifestText(manifestPath, yaml)
  val loaded = DecompositionManifestCodec.load(manifestPath)
  projectCurrentSubtaskStatus(repoRoot, loaded)
  DecompositionManifestWriteResult(manifestPath = manifestPath, manifest = loaded)
} catch (_: IOException) {
  null
}

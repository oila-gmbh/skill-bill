@file:Suppress("LongParameterList")

package skillbill.application

import skillbill.application.model.DecompositionManifestRuntimeUpdate
import skillbill.application.model.DecompositionManifestWriteRequest
import skillbill.application.model.DecompositionManifestWriteResult
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.ports.workflow.UnavailableDecompositionManifestFileStore
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.DecompositionManifest
import java.io.IOException
import java.nio.file.Path

private const val DECOMPOSITION_MODE: String = "decompose"
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
    fileStore: DecompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
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
      fileStore = fileStore,
    ) ?: return null
    return writeProjection(repoRoot, manifest, fileStore = fileStore)
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
    fileStore: DecompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
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
      manifestFromDecompositionPlan(repoRoot, plan, artifactsPatch, existingArtifacts, fileStore)
    } else {
      updatedExistingManifest(repoRoot, update, fileStore)
    }
  }

  fun maybeWriteFromWorkflowUpdate(
    repoRoot: Path,
    existingArtifactsJson: String,
    artifactsPatch: Map<String, Any?>?,
    fileStore: DecompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
  ): Path? = writeFromWorkflowUpdate(repoRoot, existingArtifactsJson, artifactsPatch, fileStore = fileStore)
    ?.manifestPath

  fun writeProjectionFromWorkflowState(
    repoRoot: Path,
    artifactsJson: String,
    fileStore: DecompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
  ): DecompositionManifestWriteResult? {
    val artifacts = decodeArtifacts(artifactsJson)
    val runtime = artifacts[DECOMPOSITION_RUNTIME_ARTIFACT_KEY].asStringAnyMapOrNull()
      ?.let { decodeDecompositionManifestMap(it, DECOMPOSITION_RUNTIME_ARTIFACT_KEY) }
      ?: return null
    return try {
      writeProjection(repoRoot, runtime, runtime.manifestPath(repoRoot), fileStore)
    } catch (_: IOException) {
      null
    }
  }

  fun writeIfDecomposed(
    request: DecompositionManifestWriteRequest,
    fileStore: DecompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
  ): DecompositionManifestWriteResult? {
    if (request.planningResult["mode"]?.toString().orEmpty() != DECOMPOSITION_MODE) {
      return null
    }
    return write(request, fileStore = fileStore)
  }

  fun write(
    request: DecompositionManifestWriteRequest,
    runtimeUpdate: DecompositionManifestRuntimeUpdate? = null,
    fileStore: DecompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
  ): DecompositionManifestWriteResult {
    val manifestPath = request.manifestPath()
    val existing = loadManifestOrNull(manifestPath, fileStore)
    val manifest = request.toManifest()
      .assertExecutionModelCanReplace(existing, manifestPath)
      .withPreservedRuntimeState(existing)
      .let { candidate ->
        runtimeUpdate?.let { candidate.withRuntimeUpdate(request.repoRoot, it) } ?: candidate
      }
    val projectedManifest = manifest.gitTrackedProjection()
    val yaml = encodeDecompositionManifestYaml(projectedManifest)
    writeDecompositionManifestText(manifestPath, yaml, fileStore)
    val loaded = loadDecompositionManifest(manifestPath, fileStore)
    projectCurrentSubtaskStatus(request.repoRoot, loaded, fileStore)
    return DecompositionManifestWriteResult(manifestPath = manifestPath, manifest = loaded)
  }

  private fun manifestFromDecompositionPlan(
    repoRoot: Path,
    plan: Map<String, Any?>,
    artifactsPatch: Map<String, Any?>?,
    existingArtifacts: Map<String, Any?>,
    fileStore: DecompositionManifestFileStore,
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
    val existing = runtimeManifestFromArtifacts(existingArtifacts) ?: loadManifestOrNull(manifestPath, fileStore)
    return request.toManifest()
      .assertExecutionModelCanReplace(existing, manifestPath)
      .withPreservedRuntimeState(existing)
  }

  private fun updatedExistingManifest(
    repoRoot: Path,
    runtimeUpdate: DecompositionManifestRuntimeUpdate,
    fileStore: DecompositionManifestFileStore,
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
    val existing = runtime ?: manifestPath?.let { loadManifestOrNull(it, fileStore) } ?: return null
    return existing.withRuntimeUpdate(repoRoot, runtimeUpdate)
  }

  private fun DecompositionManifestWriteRequest.toManifest(): DecompositionManifest {
    val subtasks = parseSubtasks(planningResult, parentSpecPath.toString())
    val currentId = currentSubtaskId
      ?: planningResult.optionalIntValue("current_subtask_id", parentSpecPath.toString())
      ?: planningResult.optionalIntValue("recommended_first_subtask_id", parentSpecPath.toString())
      ?: subtasks.first().id
    val currentSubtask = subtasks.firstOrNull { it.id == currentId }
      ?: invalidManifest(
        parentSpecPath.toString(),
        "current subtask id '$currentId' does not reference a planned subtask.",
      )
    val parentDirectory = resolvedParentSpecPath(repoRoot, parentSpecPath).parent
    val manifestDirectory = decompositionManifestDirectory(repoRoot, parentSpecPath, subtasks.map { it.specPath })
    val (issueKey, parsedFeatureName) = issueAndFeature(parentDirectory.fileName.toString())
    val featureName = if (manifestDirectory != parentDirectory) {
      manifestDirectory.fileName.toString()
    } else {
      parsedFeatureName
    }
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

private fun DecompositionManifestWriteRequest.manifestPath(): Path = decompositionManifestPath(
  repoRoot,
  parentSpecPath,
  parseSubtasks(planningResult, parentSpecPath.toString()).map {
    it.specPath
  },
)

private fun DecompositionManifest.manifestPath(repoRoot: Path): Path =
  decompositionManifestPath(repoRoot, Path.of(parentSpecPath), subtasks.map { it.specPath })

private fun runtimeManifestFromArtifacts(artifacts: Map<String, Any?>): DecompositionManifest? =
  artifacts[DECOMPOSITION_RUNTIME_ARTIFACT_KEY].asStringAnyMapOrNull()
    ?.let { decodeDecompositionManifestMap(it, DECOMPOSITION_RUNTIME_ARTIFACT_KEY) }

private fun writeProjection(
  repoRoot: Path,
  manifest: DecompositionManifest,
  manifestPath: Path = manifest.manifestPath(repoRoot),
  fileStore: DecompositionManifestFileStore,
): DecompositionManifestWriteResult? = try {
  val yaml = encodeDecompositionManifestYaml(manifest.gitTrackedProjection())
  writeDecompositionManifestText(manifestPath, yaml, fileStore)
  val loaded = loadDecompositionManifest(manifestPath, fileStore)
  projectCurrentSubtaskStatus(repoRoot, loaded, fileStore)
  DecompositionManifestWriteResult(manifestPath = manifestPath, manifest = loaded)
} catch (_: IOException) {
  null
}

private fun DecompositionManifest.gitTrackedProjection(): DecompositionManifest =
  copy(subtasks = subtasks.map { subtask -> subtask.copy(commitSha = null) })

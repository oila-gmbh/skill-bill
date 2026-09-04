package skillbill.application.decomposition

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.model.DecompositionManifestRuntimeUpdate
import skillbill.application.decomposition.model.DecompositionManifestWorkflowProjectionInput
import skillbill.application.decomposition.model.DecompositionManifestWriteRequest
import skillbill.application.decomposition.model.DecompositionPlanManifestInput
import skillbill.application.decomposition.model.PreparedDecompositionManifestWrite
import skillbill.contracts.issuekey.issueAndFeature
import skillbill.error.InvalidDecompositionManifestSchemaError
import skillbill.ports.decomposition.DecompositionManifestProjectionWriter
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestStore
import skillbill.ports.workflow.decomposition.runtime.model.DecompositionManifestWriteResult
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionManifestPlan
import java.io.IOException
import java.nio.file.Path

private const val DECOMPOSITION_MODE: String = "decompose"
const val DECOMPOSITION_RUNTIME_ARTIFACT_KEY: String = "decomposition_runtime"

@Inject
class DecompositionManifestWriter : DecompositionManifestProjectionWriter {
  fun writeFromWorkflowUpdate(input: DecompositionManifestWorkflowProjectionInput): DecompositionManifestWriteResult? {
    val manifest = manifestFromWorkflowUpdate(input) ?: return null
    return writeProjection(input.repoRoot, manifest, input.validator, fileStore = input.fileStore)
  }

  fun manifestFromWorkflowUpdate(input: DecompositionManifestWorkflowProjectionInput): DecompositionManifest? {
    val existingArtifacts = decodeArtifacts(input.existingArtifactsJson)
    val update = input.runtimeUpdate.copy(
      artifactsPatch = input.artifactsPatch,
      existingArtifacts = existingArtifacts,
    )
    val plan = input.artifactsPatch?.get("plan").asStringAnyMapOrNull()
    return if (plan != null && plan["mode"] == DECOMPOSITION_MODE) {
      manifestFromDecompositionPlan(
        DecompositionPlanManifestInput(
          repoRoot = input.repoRoot,
          plan = plan,
          artifactsPatch = input.artifactsPatch,
          existingArtifacts = existingArtifacts,
          validator = input.validator,
          fileStore = input.fileStore,
        ),
      )
    } else {
      updatedExistingManifest(input.repoRoot, update, input.validator, input.fileStore)
    }
  }

  fun maybeWriteFromWorkflowUpdate(input: DecompositionManifestWorkflowProjectionInput): Path? =
    writeFromWorkflowUpdate(input)?.manifestPath

  override fun writeProjectionFromWorkflowState(
    repoRoot: Path,
    artifactsJson: String,
    validator: DecompositionManifestValidator,
    fileStore: DecompositionManifestStore,
  ): DecompositionManifestWriteResult? {
    val artifacts = decodeArtifacts(artifactsJson)
    val runtime = artifacts[DECOMPOSITION_RUNTIME_ARTIFACT_KEY].asStringAnyMapOrNull()
      ?.let { decodeDecompositionManifestMap(it, validator, DECOMPOSITION_RUNTIME_ARTIFACT_KEY) }
      ?: return null
    return try {
      writeProjection(repoRoot, runtime, validator, runtime.manifestPath(repoRoot), fileStore)
    } catch (_: IOException) {
      null
    }
  }

  fun writeIfDecomposed(
    request: DecompositionManifestWriteRequest,
    validator: DecompositionManifestValidator,
    fileStore: DecompositionManifestStore = UnavailableDecompositionManifestStore,
  ): DecompositionManifestWriteResult? {
    if (request.planningResult["mode"]?.toString().orEmpty() != DECOMPOSITION_MODE) {
      return null
    }
    return write(request, validator, fileStore = fileStore)
  }

  fun write(
    request: DecompositionManifestWriteRequest,
    validator: DecompositionManifestValidator,
    runtimeUpdate: DecompositionManifestRuntimeUpdate? = null,
    fileStore: DecompositionManifestStore = UnavailableDecompositionManifestStore,
  ): DecompositionManifestWriteResult {
    val prepared = prepare(request, validator, runtimeUpdate, fileStore)
    writeDecompositionManifestText(prepared.manifestPath, prepared.yaml, fileStore)
    val loaded = loadValidatedDecompositionManifest(prepared.manifestPath, fileStore, validator)
    return DecompositionManifestWriteResult(
      manifestPath = prepared.manifestPath,
      manifest = loaded.manifest,
      repairEvidence = prepared.repairEvidence + listOfNotNull(loaded.repairEvidence),
    )
  }

  fun prepare(
    request: DecompositionManifestWriteRequest,
    validator: DecompositionManifestValidator,
    runtimeUpdate: DecompositionManifestRuntimeUpdate? = null,
    fileStore: DecompositionManifestStore = UnavailableDecompositionManifestStore,
  ): PreparedDecompositionManifestWrite {
    assertParentSpecIsNotDecomposedSubtask(request.repoRoot, request.parentSpecPath, validator, fileStore)
    val manifestPath = request.manifestPath()
    val existingLoad = loadValidatedDecompositionManifestOrNull(manifestPath, fileStore, validator)
    val existing = existingLoad?.manifest
    val manifest = request.toManifest()
      .assertExecutionModelCanReplace(existing, manifestPath)
      .withPreservedRuntimeState(existing)
      .let { candidate ->
        runtimeUpdate?.let { candidate.withRuntimeUpdate(request.repoRoot, it) } ?: candidate
      }
    val projectedManifest = manifest.gitTrackedProjection()
    val encoded = encodeValidatedDecompositionManifestYaml(projectedManifest, validator, fileStore)
    return PreparedDecompositionManifestWrite(
      manifestPath = manifestPath,
      manifest = projectedManifest,
      yaml = encoded.yamlText,
      repairEvidence = listOfNotNull(existingLoad?.repairEvidence, encoded.repairEvidence),
    )
  }

  private fun manifestFromDecompositionPlan(input: DecompositionPlanManifestInput): DecompositionManifest {
    val parentSpecPath = Path.of(parentSpecPath(input.plan))
    assertParentSpecIsNotDecomposedSubtask(input.repoRoot, parentSpecPath, input.validator, input.fileStore)
    val branchName = branchName(input.artifactsPatch?.get("branch"))
      .ifBlank { branchName(input.existingArtifacts["branch"]) }
    val executionModel = executionModel(input.plan)
    val request = DecompositionManifestWriteRequest(
      repoRoot = input.repoRoot,
      parentSpecPath = parentSpecPath,
      planningResult = input.plan,
      baseBranch = baseBranch(input.plan, parentSpecPath.toString()),
      featureBranch = when (executionModel) {
        DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK ->
          branchName.ifBlank { defaultFeatureBranch(parentSpecPath) }
        DecompositionExecutionModel.STACKED_BRANCHES -> null
      },
      executionModel = executionModel,
      stackBranches = parseStackBranches(input.plan),
      specSource = specSource(input.plan),
    )
    val manifestPath = request.manifestPath()
    val existing = runtimeManifestFromArtifacts(input.existingArtifacts, input.validator)
      ?: loadManifestOrNull(manifestPath, input.validator, input.fileStore)
    return request.toManifest()
      .assertExecutionModelCanReplace(existing, manifestPath)
      .withPreservedRuntimeState(existing)
  }

  private fun updatedExistingManifest(
    repoRoot: Path,
    runtimeUpdate: DecompositionManifestRuntimeUpdate,
    validator: DecompositionManifestValidator,
    fileStore: DecompositionManifestStore,
  ): DecompositionManifest? {
    val artifacts = LinkedHashMap(runtimeUpdate.existingArtifacts).apply {
      runtimeUpdate.artifactsPatch?.let(::putAll)
    }
    val runtime = runtimeManifestFromArtifacts(artifacts, validator)
    val manifestPath = manifestPathFromArtifacts(
      repoRoot = repoRoot,
      artifactsPatch = runtimeUpdate.artifactsPatch,
      existingArtifacts = runtimeUpdate.existingArtifacts,
    ) ?: runtime?.manifestPath(repoRoot)
    val existing = runtime ?: manifestPath?.let { loadManifestOrNull(it, validator, fileStore) } ?: return null
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
    val typedPlan = DecompositionManifestPlan(
      parentSpecPath = repoRelativePath(repoRoot, parentSpecPath),
      baseBranch = baseBranch,
      featureBranch = featureBranch,
      specSource = specSource,
      executionModel = executionModel,
      stackBranches = stackBranches,
      currentSubtaskId = currentSubtask.id,
      subtasks = subtasks,
    )
    return DecompositionManifest(
      issueKey = issueKey,
      featureName = featureName,
      parentSpecPath = typedPlan.parentSpecPath,
      specSource = typedPlan.specSource,
      executionModel = typedPlan.executionModel,
      baseBranch = typedPlan.baseBranch,
      featureBranch = typedPlan.featureBranch,
      stackBranches = typedPlan.stackBranches,
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = typedPlan.currentSubtaskId, action = "start"),
      subtasks = typedPlan.subtasks,
    )
  }
}

private fun assertParentSpecIsNotDecomposedSubtask(
  repoRoot: Path,
  parentSpecPath: Path,
  validator: DecompositionManifestValidator,
  fileStore: DecompositionManifestStore,
) {
  val normalizedParentSpec = resolvedParentSpecPath(repoRoot, parentSpecPath).normalize()
  val parentSpecLabel = repoRelativePath(repoRoot, parentSpecPath)
  val referringManifests = fileStore.findDecompositionManifestFiles(repoRoot)
    .filterNot { manifestPath -> archivedDecompositionManifest(repoRoot, manifestPath) }
    .mapNotNull { manifestPath ->
      val manifest = try {
        loadDecompositionManifest(manifestPath, fileStore, validator)
      } catch (error: IOException) {
        invalidParentSpecManifestLoad(parentSpecPath, manifestPath, parentSpecLabel, error)
      } catch (error: InvalidDecompositionManifestSchemaError) {
        invalidParentSpecManifestLoad(parentSpecPath, manifestPath, parentSpecLabel, error)
      }
      val matchingSubtask = manifest.subtasks.firstOrNull { subtask ->
        resolvedParentSpecPath(repoRoot, Path.of(subtask.specPath)).normalize() == normalizedParentSpec
      } ?: return@mapNotNull null
      manifestPath to matchingSubtask.id
    }
  if (referringManifests.isNotEmpty()) {
    val references = referringManifests.joinToString(", ") { (manifestPath, subtaskId) ->
      "$manifestPath (subtask_id=$subtaskId)"
    }
    invalidManifest(
      parentSpecPath.toString(),
      "parent_spec_path '$parentSpecLabel' is already a decomposed subtask in $references; " +
        "nested decomposition of subtask specs is not supported.",
    )
  }
}

private fun invalidParentSpecManifestLoad(
  parentSpecPath: Path,
  manifestPath: Path,
  parentSpecLabel: String,
  error: Exception,
): Nothing {
  val detail = error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName.orEmpty()
  invalidManifest(
    parentSpecPath.toString(),
    "failed to load decomposition manifest '$manifestPath' while validating parent_spec_path " +
      "'$parentSpecLabel': $detail",
  )
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

private fun runtimeManifestFromArtifacts(
  artifacts: Map<String, Any?>,
  validator: DecompositionManifestValidator,
): DecompositionManifest? = artifacts[DECOMPOSITION_RUNTIME_ARTIFACT_KEY].asStringAnyMapOrNull()
  ?.let { decodeDecompositionManifestMap(it, validator, DECOMPOSITION_RUNTIME_ARTIFACT_KEY) }

private fun writeProjection(
  repoRoot: Path,
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
  manifestPath: Path = manifest.manifestPath(repoRoot),
  fileStore: DecompositionManifestStore,
): DecompositionManifestWriteResult? = try {
  val encoded = encodeValidatedDecompositionManifestYaml(manifest.gitTrackedProjection(), validator, fileStore)
  writeDecompositionManifestText(manifestPath, encoded.yamlText, fileStore)
  val loaded = loadValidatedDecompositionManifest(manifestPath, fileStore, validator)
  DecompositionManifestWriteResult(
    manifestPath = manifestPath,
    manifest = loaded.manifest,
    repairEvidence = listOfNotNull(encoded.repairEvidence, loaded.repairEvidence),
  )
} catch (_: IOException) {
  null
}

private fun DecompositionManifest.gitTrackedProjection(): DecompositionManifest =
  copy(subtasks = subtasks.map { subtask -> subtask.copy(commitSha = null) })

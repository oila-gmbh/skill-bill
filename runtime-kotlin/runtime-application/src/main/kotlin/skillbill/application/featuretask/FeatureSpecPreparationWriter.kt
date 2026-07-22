package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.defaultFeatureBranch
import skillbill.application.decomposition.repoRelativePath
import skillbill.application.model.DecompositionManifestWriteRequest
import skillbill.error.InvalidFeatureSpecPreparationRequestError
import skillbill.featurespec.model.FeatureSpecPreparationMode
import skillbill.featurespec.model.FeatureSpecSubtaskPreparation
import skillbill.featurespec.model.FeatureSpecWriteRequest
import skillbill.featurespec.model.FeatureSpecWriteResult
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.ports.workflow.UnavailableDecompositionManifestFileStore
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.model.SpecSource
import java.nio.file.Path

@Inject
class FeatureSpecPreparationWriter(
  private val decompositionManifestValidator: DecompositionManifestValidator,
  private val fileStore: DecompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
) {
  fun write(repoRoot: Path, request: FeatureSpecWriteRequest): FeatureSpecWriteResult {
    val issueKey = request.decision.issueKey.trim()
    val featureName = normalizeFeatureName(request.featureName)
    if (featureName.isBlank()) {
      invalidRequest("feature_name", "feature name is required.")
    }
    val specDirectory = repoRoot.resolve(".feature-specs/$issueKey-$featureName")
    val parentSpecPath = specDirectory.resolve("spec.md")
    val parentSpecRelativePath = repoRelativePath(repoRoot, parentSpecPath)
    return writePreparedFeature(
      repoRoot = repoRoot,
      request = request,
      parentSpecPath = parentSpecPath,
      parentSpecRelativePath = parentSpecRelativePath,
    )
  }

  private fun writePreparedFeature(
    repoRoot: Path,
    request: FeatureSpecWriteRequest,
    parentSpecPath: Path,
    parentSpecRelativePath: String,
  ): FeatureSpecWriteResult {
    validateSubtasks(request.subtasks, request.specSource)
    val parentSpecText = renderParentSpec(
      ParentSpecRenderInput(
        issueKey = request.decision.issueKey,
        featureName = normalizeFeatureName(request.featureName),
        mode = request.decision.mode,
        intendedOutcome = request.decision.intendedOutcome,
        acceptanceCriteria = request.decision.acceptanceCriteria,
        constraints = request.decision.constraints,
        nonGoals = request.decision.nonGoals,
        overview = request.parentSpecOverview,
        validationStrategy = request.validationStrategy,
      ),
    )
    val subtaskRecords = prepareSubtasks(repoRoot, request, parentSpecPath, parentSpecRelativePath)
    val planningResult = linkedMapOf(
      "mode" to "decompose",
      "parent_spec_path" to parentSpecPath.toString(),
      "recommended_first_subtask_id" to subtaskRecords.first().definition.id,
      "subtasks" to subtaskRecords.map { subtask ->
        linkedMapOf(
          "id" to subtask.definition.id,
          "name" to subtask.definition.name,
          "spec_path" to subtask.path.toString(),
          "depends_on" to subtask.definition.dependsOn,
          "scope" to subtask.definition.scope,
          "linear_issue_id" to subtask.definition.linearIssueId,
        )
      },
    )
    val preparedManifest = DecompositionManifestWriter.prepare(
      request = DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = planningResult,
        baseBranch = request.baseBranch.ifBlank { "main" },
        featureBranch = request.featureBranch.takeIf(String::isNotBlank) ?: defaultFeatureBranch(parentSpecPath),
        specSource = request.specSource,
      ),
      validator = decompositionManifestValidator,
      fileStore = fileStore,
    )
    fileStore.writeTextAtomically(parentSpecPath, parentSpecText)
    subtaskRecords.forEach { fileStore.writeTextAtomically(it.path, it.text) }
    fileStore.writeTextAtomically(preparedManifest.manifestPath, preparedManifest.yaml)
    return FeatureSpecWriteResult(
      mode = request.decision.mode,
      parentSpecPath = parentSpecRelativePath,
      featureImplementPath = parentSpecRelativePath,
      decompositionManifestPath = repoRelativePath(repoRoot, preparedManifest.manifestPath),
      subtaskSpecPaths = subtaskRecords.map(PreparedSubtask::relativePath),
    )
  }

  private fun prepareSubtasks(
    repoRoot: Path,
    request: FeatureSpecWriteRequest,
    parentSpecPath: Path,
    parentSpecRelativePath: String,
  ): List<PreparedSubtask> = request.subtasks.map { subtask ->
    val subtaskPath = parentSpecPath.parent.resolve(subtaskFileName(subtask))
    val subtaskRelativePath = repoRelativePath(repoRoot, subtaskPath)
    PreparedSubtask(
      path = subtaskPath,
      relativePath = subtaskRelativePath,
      definition = subtask,
      text = renderSubtaskSpec(
        issueKey = request.decision.issueKey,
        subtask = subtask,
        parentSpecPath = parentSpecRelativePath,
        subtaskPath = subtaskRelativePath,
      ),
    )
  }

  private fun validateSubtasks(subtasks: List<FeatureSpecSubtaskPreparation>, specSource: SpecSource) {
    if (subtasks.isEmpty()) {
      invalidRequest("subtasks", "prepared features require at least one ordered subtask.")
    }
    val ids = mutableSetOf<Int>()
    var previousId = Int.MIN_VALUE
    subtasks.forEachIndexed { index, subtask ->
      if (subtask.id <= 0) {
        invalidRequest("subtasks[$index].id", "id must be a positive integer.")
      }
      if (!ids.add(subtask.id)) {
        invalidRequest("subtasks[$index].id", "id must be unique.")
      }
      if (subtask.id <= previousId) {
        invalidRequest("subtasks[$index].id", "subtask ids must be in ascending dependency order.")
      }
      previousId = subtask.id
      if (subtask.scope.isBlank()) {
        invalidRequest("subtasks[$index].scope", "scope is required.")
      }
      if (subtask.acceptanceCriteria.isEmpty()) {
        invalidRequest("subtasks[$index].acceptance_criteria", "at least one acceptance criterion is required.")
      }
      if (subtask.validationStrategy.isBlank()) {
        invalidRequest("subtasks[$index].validation_strategy", "validation strategy is required.")
      }
      if (subtask.nextPath.isBlank()) {
        invalidRequest("subtasks[$index].next_path", "next path is required.")
      }
      if (subtask.linearIssueId.isNullOrBlank() && specSource == SpecSource.LINEAR) {
        invalidRequest("subtasks[$index].linear_issue_id", "linear source requires a Linear issue id.")
      }
      subtask.dependsOn.forEachIndexed { dependencyIndex, dependencyId ->
        if (dependencyId >= subtask.id || dependencyId !in ids) {
          invalidRequest(
            "subtasks[$index].depends_on[$dependencyIndex]",
            "depends_on must reference an existing earlier subtask id.",
          )
        }
      }
    }
  }
}

private data class PreparedSubtask(
  val path: Path,
  val relativePath: String,
  val definition: FeatureSpecSubtaskPreparation,
  val text: String,
)

private data class ParentSpecRenderInput(
  val issueKey: String,
  val featureName: String,
  val mode: FeatureSpecPreparationMode,
  val intendedOutcome: String,
  val acceptanceCriteria: List<String>,
  val constraints: List<String>,
  val nonGoals: List<String>,
  val overview: String,
  val validationStrategy: String,
)

private fun renderParentSpec(input: ParentSpecRenderInput): String = buildString {
  appendLine("# ${input.issueKey} - ${input.featureName}")
  appendLine()
  appendLine("## Mode")
  appendLine()
  appendLine(input.mode.wireValue)
  appendLine()
  appendLine("## Intended Outcome")
  appendLine()
  appendLine(input.intendedOutcome.ifBlank { "(none provided)" })
  appendLine()
  appendLine("## Overview")
  appendLine()
  appendLine(input.overview.ifBlank { "(none provided)" })
  appendLine()
  appendLine("## Acceptance Criteria")
  appendLine()
  input.acceptanceCriteria.forEachIndexed { index, criterion ->
    appendLine("${index + 1}. $criterion")
  }
  appendLine()
  appendLine("## Constraints")
  appendLine()
  input.constraints.forEach { constraint ->
    appendLine("- $constraint")
  }
  appendLine()
  appendLine("## Non-Goals")
  appendLine()
  if (input.nonGoals.isEmpty()) {
    appendLine("- None")
  } else {
    input.nonGoals.forEach { nonGoal -> appendLine("- $nonGoal") }
  }
  appendLine()
  appendLine("## Validation Strategy")
  appendLine()
  appendLine(input.validationStrategy.ifBlank { "bill-code-check" })
}

private fun renderSubtaskSpec(
  issueKey: String,
  subtask: FeatureSpecSubtaskPreparation,
  parentSpecPath: String,
  subtaskPath: String,
): String = buildString {
  appendLine("# $issueKey Subtask ${subtask.id} - ${subtask.name}")
  appendLine()
  appendLine("Parent spec: [$parentSpecPath](./spec.md)")
  appendLine("Issue key: $issueKey")
  appendLine()
  appendLine("## Scope")
  appendLine()
  appendLine(subtask.scope)
  appendLine()
  appendLine("## Acceptance Criteria")
  appendLine()
  subtask.acceptanceCriteria.forEachIndexed { index, criterion ->
    appendLine("${index + 1}. $criterion")
  }
  appendLine()
  appendLine("## Non-Goals")
  appendLine()
  if (subtask.nonGoals.isEmpty()) {
    appendLine("- None")
  } else {
    subtask.nonGoals.forEach { nonGoal -> appendLine("- $nonGoal") }
  }
  appendLine()
  appendLine("## Dependency Notes")
  appendLine()
  if (subtask.dependsOn.isEmpty()) {
    appendLine("Depends on: none")
  } else {
    appendLine("Depends on: ${subtask.dependsOn.joinToString(", ")}")
  }
  appendLine(subtask.dependencyNotes.ifBlank { "Dependency order is captured by depends_on in the manifest." })
  appendLine()
  appendLine("## Validation Strategy")
  appendLine()
  appendLine(subtask.validationStrategy)
  appendLine()
  appendLine("## Next Path")
  appendLine()
  appendLine(subtask.nextPath)
  appendLine()
  appendLine("## Spec Path")
  appendLine()
  appendLine(subtaskPath)
}

private fun normalizeFeatureName(raw: String): String = raw
  .trim()
  .lowercase()
  .replace(Regex("[^a-z0-9]+"), "-")
  .trim('-')
  .ifBlank { "feature" }

private fun subtaskFileName(subtask: FeatureSpecSubtaskPreparation): String =
  "spec_subtask_${subtask.id}_${normalizeFeatureName(subtask.name)}.md"

private fun invalidRequest(fieldPath: String, reason: String): Nothing =
  throw InvalidFeatureSpecPreparationRequestError(fieldPath = fieldPath, reason = reason)

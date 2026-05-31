package skillbill.application

import skillbill.application.model.DecompositionManifestWriteRequest
import skillbill.error.FeatureSpecPreparationModeConflictError
import skillbill.error.InvalidFeatureSpecPreparationRequestError
import skillbill.featurespec.model.FeatureSpecPreparationDecision
import skillbill.featurespec.model.FeatureSpecPreparationMode
import skillbill.featurespec.model.FeatureSpecSubtaskPreparation
import skillbill.featurespec.model.FeatureSpecWriteRequest
import skillbill.featurespec.model.FeatureSpecWriteResult
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.ports.workflow.UnavailableDecompositionManifestFileStore
import skillbill.workflow.DecompositionManifestValidator
import java.nio.file.Path

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
    val decompositionManifestPath = specDirectory.resolve(DECOMPOSITION_MANIFEST_FILENAME)
    return when (request.decision.mode) {
      FeatureSpecPreparationMode.SINGLE_SPEC -> {
        validateSingleSpecRequest(request)
        assertNoDecompositionManifest(issueKey, decompositionManifestPath)
        writeParentSpec(
          parentSpecPath = parentSpecPath,
          decision = request.decision,
          featureName = featureName,
          parentSpecOverview = request.parentSpecOverview,
          validationStrategy = request.validationStrategy,
        )
        FeatureSpecWriteResult(
          mode = FeatureSpecPreparationMode.SINGLE_SPEC,
          parentSpecPath = parentSpecRelativePath,
          featureImplementPath = parentSpecRelativePath,
          decompositionManifestPath = null,
          subtaskSpecPaths = emptyList(),
        )
      }

      FeatureSpecPreparationMode.DECOMPOSED -> writeDecomposed(
        repoRoot = repoRoot,
        request = request,
        parentSpecPath = parentSpecPath,
        parentSpecRelativePath = parentSpecRelativePath,
      )
    }
  }

  private fun writeDecomposed(
    repoRoot: Path,
    request: FeatureSpecWriteRequest,
    parentSpecPath: Path,
    parentSpecRelativePath: String,
  ): FeatureSpecWriteResult {
    validateDecomposedSubtasks(request.subtasks)
    writeParentSpec(
      parentSpecPath = parentSpecPath,
      decision = request.decision,
      featureName = normalizeFeatureName(request.featureName),
      parentSpecOverview = request.parentSpecOverview,
      validationStrategy = request.validationStrategy,
    )
    val subtaskRecords = request.subtasks.map { subtask ->
      val subtaskPath = parentSpecPath.parent.resolve(subtaskFileName(subtask))
      val subtaskRelativePath = repoRelativePath(repoRoot, subtaskPath)
      fileStore.writeTextAtomically(
        subtaskPath,
        renderSubtaskSpec(
          issueKey = request.decision.issueKey,
          subtask = subtask,
          parentSpecPath = parentSpecRelativePath,
          subtaskPath = subtaskRelativePath,
        ),
      )
      PreparedSubtask(path = subtaskPath, relativePath = subtaskRelativePath, definition = subtask)
    }
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
        )
      },
    )
    val manifestWriteResult = DecompositionManifestWriter.write(
      request = DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = planningResult,
        baseBranch = request.baseBranch.ifBlank { "main" },
        featureBranch = request.featureBranch.takeIf(String::isNotBlank) ?: defaultFeatureBranch(parentSpecPath),
      ),
      validator = decompositionManifestValidator,
      fileStore = fileStore,
    )
    return FeatureSpecWriteResult(
      mode = FeatureSpecPreparationMode.DECOMPOSED,
      parentSpecPath = parentSpecRelativePath,
      featureImplementPath = parentSpecRelativePath,
      decompositionManifestPath = repoRelativePath(repoRoot, manifestWriteResult.manifestPath),
      subtaskSpecPaths = subtaskRecords.map(PreparedSubtask::relativePath),
    )
  }

  private fun validateSingleSpecRequest(request: FeatureSpecWriteRequest) {
    if (request.subtasks.isNotEmpty()) {
      invalidRequest("subtasks", "single_spec mode cannot include decomposition subtasks.")
    }
  }

  private fun assertNoDecompositionManifest(issueKey: String, manifestPath: Path) {
    if (!fileStore.isRegularFile(manifestPath)) {
      return
    }
    throw FeatureSpecPreparationModeConflictError(
      issueKey = issueKey,
      requestedMode = FeatureSpecPreparationMode.SINGLE_SPEC.wireValue,
      conflictingPath = manifestPath.toString(),
      reason = "single_spec cannot run beside an existing decomposition manifest.",
    )
  }

  private fun writeParentSpec(
    parentSpecPath: Path,
    decision: FeatureSpecPreparationDecision,
    featureName: String,
    parentSpecOverview: String,
    validationStrategy: String,
  ) {
    fileStore.writeTextAtomically(
      parentSpecPath,
      renderParentSpec(
        ParentSpecRenderInput(
          issueKey = decision.issueKey,
          featureName = featureName,
          mode = decision.mode,
          intendedOutcome = decision.intendedOutcome,
          acceptanceCriteria = decision.acceptanceCriteria,
          constraints = decision.constraints,
          nonGoals = decision.nonGoals,
          overview = parentSpecOverview,
          validationStrategy = validationStrategy,
        ),
      ),
    )
  }

  private fun validateDecomposedSubtasks(subtasks: List<FeatureSpecSubtaskPreparation>) {
    if (subtasks.size < 2) {
      invalidRequest("subtasks", "decomposed mode requires at least two ordered subtasks.")
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
      subtask.dependsOn.forEachIndexed { dependencyIndex, dependencyId ->
        if (dependencyId >= subtask.id) {
          invalidRequest(
            "subtasks[$index].depends_on[$dependencyIndex]",
            "depends_on must reference an earlier subtask id.",
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
  appendLine("---")
  appendLine("status: In Progress")
  appendLine("---")
  appendLine()
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
  appendLine(input.validationStrategy.ifBlank { "bill-quality-check" })
}

private fun renderSubtaskSpec(
  issueKey: String,
  subtask: FeatureSpecSubtaskPreparation,
  parentSpecPath: String,
  subtaskPath: String,
): String = buildString {
  appendLine("---")
  appendLine("status: Pending")
  appendLine("---")
  appendLine()
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

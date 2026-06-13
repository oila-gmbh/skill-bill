package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.repoRoot
import skillbill.featurespec.model.FeatureSpecPreparationIntake
import skillbill.featurespec.model.FeatureSpecPreparationMode
import skillbill.featurespec.model.FeatureSpecSubtaskPreparation
import skillbill.featurespec.model.FeatureSpecWriteRequest
import skillbill.featurespec.model.FeatureSpecWriteResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposePlanOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeSubtask
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import java.nio.file.Path

@Inject
class FeatureTaskRuntimeDecompositionPlanner(
  private val preparationRuntime: FeatureSpecPreparationRuntime,
  private val preparationWriter: FeatureSpecPreparationWriter,
) {
  fun writeDecomposition(
    repoRoot: Path,
    issueKey: String,
    runInvariants: FeatureTaskRuntimeRunInvariants,
    outcome: FeatureTaskRuntimeDecomposePlanOutcome,
  ): FeatureSpecWriteResult {
    val decision = preparationRuntime.prepareForFeatureSpec(
      FeatureSpecPreparationIntake(
        issueKey = issueKey,
        intendedOutcome = outcome.parentSpecOverview.ifBlank { outcome.reason },
        acceptanceCriteria = runInvariants.acceptanceCriteria,
        constraints = runInvariants.mandatesAndOverrides.ifEmpty { listOf("Runtime decompose planning stop.") },
        nonGoals = emptyList(),
      ),
    ).copy(mode = FeatureSpecPreparationMode.DECOMPOSED)
    return preparationWriter.write(
      repoRoot = repoRoot,
      request = FeatureSpecWriteRequest(
        decision = decision,
        featureName = outcome.featureName,
        parentSpecOverview = outcome.parentSpecOverview,
        validationStrategy = outcome.validationStrategy,
        subtasks = outcome.subtasks.map(FeatureTaskRuntimeDecomposeSubtask::toPreparation),
        baseBranch = outcome.baseBranch,
        featureBranch = outcome.featureBranch,
      ),
    )
  }
}

private fun FeatureTaskRuntimeDecomposeSubtask.toPreparation(): FeatureSpecSubtaskPreparation =
  FeatureSpecSubtaskPreparation(
    id = id,
    name = name,
    scope = scope,
    acceptanceCriteria = acceptanceCriteria,
    nonGoals = nonGoals,
    dependencyNotes = dependencyNotes,
    validationStrategy = validationStrategy,
    nextPath = nextPath,
    dependsOn = dependsOn,
  )

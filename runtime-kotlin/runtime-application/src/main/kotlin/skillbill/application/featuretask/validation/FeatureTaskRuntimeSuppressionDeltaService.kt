package skillbill.application.featuretask.validation

import me.tatarka.inject.annotations.Inject
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.scopedPathContentsAgainstBase
import skillbill.scaffold.model.ValidationGateDeclaration

/**
 * Measures pack-declared suppression markers on the validate-boundary path inventory
 * against [baseRef] via the git operations port.
 */
@Inject
class FeatureTaskRuntimeSuppressionDeltaService(
  private val gitOperations: WorkflowGitOperations,
) {
  fun measure(
    repoRoot: java.nio.file.Path,
    baseRef: String,
    changedPaths: List<String>,
    declaration: ValidationGateDeclaration,
  ): Result<SuppressionDelta> {
    val markers = declaration.suppressionMarkers
    if (markers.isEmpty()) {
      return Result.success(SuppressionDelta(gated = false, introductions = emptyList()))
    }
    if (baseRef.isBlank()) {
      return Result.failure(
        IllegalStateException(
          "Validation suppression gate requires a base ref when the pack declares suppression_markers.",
        ),
      )
    }
    val contents = gitOperations.scopedPathContentsAgainstBase(repoRoot, baseRef, changedPaths)
    if (!contents.ok) {
      return Result.failure(
        IllegalStateException(
          contents.error.ifBlank { "Suppression evidence read failed." },
        ),
      )
    }
    return Result.success(SuppressionDeltaMeasurer.measure(markers, contents.pairs))
  }
}

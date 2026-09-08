package skillbill.application.goalrunner.planning
import skillbill.application.goalplanning.sha256HexUtf8
import skillbill.contracts.JsonSupport
import skillbill.workflow.decomposition.model.DecompositionManifest

fun goalPlanningImmutableDecompositionHash(manifest: DecompositionManifest): String {
  val immutable = linkedMapOf<String, Any?>(
    "contract_version" to manifest.contractVersion,
    "issue_key" to manifest.issueKey,
    "feature_name" to manifest.featureName,
    "parent_spec_path" to manifest.parentSpecPath,
    "spec_source" to manifest.specSource.wireValue,
    "execution_model" to manifest.executionModel.wireValue,
    "base_branch" to manifest.baseBranch,
    "feature_branch" to manifest.featureBranch,
    "stack_branches" to manifest.stackBranches.map {
      linkedMapOf("subtask_id" to it.subtaskId, "branch" to it.branch, "base_branch" to it.baseBranch)
    },
    "subtasks" to manifest.subtasks.map { subtask ->
      linkedMapOf(
        "id" to subtask.id,
        "name" to subtask.name,
        "spec_path" to subtask.specPath,
        "linear_issue_id" to subtask.linearIssueId,
        "dependencies" to subtask.dependencies.map { dependency ->
          linkedMapOf(
            "subtask_id" to dependency.subtaskId,
            "optional" to dependency.optional,
            "skipped" to dependency.skipped,
          )
        },
      )
    },
  )
  return sha256HexUtf8(JsonSupport.mapToJsonString(immutable))
}

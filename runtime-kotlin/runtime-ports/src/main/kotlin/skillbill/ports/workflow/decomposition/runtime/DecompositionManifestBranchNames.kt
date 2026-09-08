package skillbill.ports.workflow.decomposition.runtime

import skillbill.contracts.issuekey.issueAndFeature
import java.nio.file.Path

fun defaultFeatureBranch(parentSpecPath: Path): String {
  val (issueKey, featureName) = issueAndFeature(parentSpecPath.parent.fileName.toString())
  return "feat/$issueKey-$featureName"
}

fun branchName(branchArtifact: Any?): String = when (branchArtifact) {
  is Map<*, *> -> branchArtifact["branch_name"]?.toString().orEmpty()
    .ifBlank { branchArtifact["branch"]?.toString().orEmpty() }
  is String -> branchArtifact
  else -> ""
}

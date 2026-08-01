package skillbill.application.decomposition

import java.nio.file.Path

internal fun defaultFeatureBranch(parentSpecPath: Path): String {
  val (issueKey, featureName) = issueAndFeature(parentSpecPath.parent.fileName.toString())
  return "feat/$issueKey-$featureName"
}

internal fun branchName(branchArtifact: Any?): String = when (branchArtifact) {
  is Map<*, *> -> branchArtifact["branch_name"]?.toString().orEmpty()
    .ifBlank { branchArtifact["branch"]?.toString().orEmpty() }
  is String -> branchArtifact
  else -> ""
}

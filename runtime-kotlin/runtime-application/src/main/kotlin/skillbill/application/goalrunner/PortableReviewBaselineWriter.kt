package skillbill.application.goalrunner

import skillbill.application.goalrunner.model.PortableReviewBaselineWriteRequest
import skillbill.ports.goalrunner.persistence.PortableReviewBaselinePersistence

class PortableReviewBaselineWriter(
  private val persistence: PortableReviewBaselinePersistence,
) {
  fun persistBeforeImplementation(request: PortableReviewBaselineWriteRequest) {
    val artifact = PortableReviewBaselineMapping.fromReviewBaseline(
      workflowId = request.workflowId,
      repositoryIdentity = request.repositoryIdentity,
      goalBranch = request.goalBranch,
      reviewBaseline = request.reviewBaseline,
    )
    val path = PortableReviewBaselinePaths.artifactPath(request.repoRoot, request.manifest, request.subtaskId)
    persistence.writeAtomically(path, artifact)
  }
}

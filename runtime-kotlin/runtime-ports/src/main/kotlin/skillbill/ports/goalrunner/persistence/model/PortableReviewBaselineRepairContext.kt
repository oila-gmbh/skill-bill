package skillbill.ports.goalrunner.persistence.model

import skillbill.workflow.decomposition.model.DecompositionManifest

data class PortableReviewBaselineRepairContext(
  val manifest: DecompositionManifest,
  val repositoryIdentity: String,
  val subtaskId: Int,
  val workflowId: String,
)

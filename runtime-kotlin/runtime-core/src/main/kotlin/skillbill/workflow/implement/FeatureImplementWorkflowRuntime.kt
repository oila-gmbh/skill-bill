package skillbill.workflow.implement

import skillbill.contracts.surface.RuntimeSurfaceContract
import skillbill.contracts.surface.RuntimeSurfaceStatus

/** Kotlin-owned durable workflow runtime surface for feature implementation state. */
object FeatureImplementWorkflowRuntime {
  val contract: RuntimeSurfaceContract = RuntimeSurfaceContract(
    name = "feature-implement-workflow",
    ownerPackage = "skillbill.workflow.implement",
    contractVersion = "0.1",
    status = RuntimeSurfaceStatus.ACTIVE,
    summary = "Feature implementation workflow state, resume, and continuation surface.",
    placeholderReason = "",
    supportedOperations = listOf("open", "update", "get", "list", "latest", "resume", "continue"),
  )
}

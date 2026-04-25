package skillbill.workflow.verify

import skillbill.contracts.surface.RuntimeSurfaceContract
import skillbill.contracts.surface.RuntimeSurfaceStatus

/** Kotlin-owned durable workflow runtime surface for feature verification state. */
object FeatureVerifyWorkflowRuntime {
  val contract: RuntimeSurfaceContract = RuntimeSurfaceContract(
    name = "feature-verify-workflow",
    ownerPackage = "skillbill.workflow.verify",
    contractVersion = "0.1",
    status = RuntimeSurfaceStatus.ACTIVE,
    summary = "Feature verification workflow state, resume, and continuation surface.",
    placeholderReason = "",
    supportedOperations = listOf("open", "update", "get", "list", "latest", "resume", "continue"),
  )
}

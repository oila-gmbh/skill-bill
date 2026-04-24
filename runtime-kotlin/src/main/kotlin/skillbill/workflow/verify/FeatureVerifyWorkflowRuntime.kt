package skillbill.workflow.verify

import skillbill.contracts.surface.RuntimeSurfaceContract
import skillbill.contracts.surface.RuntimeSurfaceStatus

/** Reserved until feature-verify orchestration moves out of the agent shell. */
object FeatureVerifyWorkflowRuntime {
  val contract: RuntimeSurfaceContract = RuntimeSurfaceContract(
    name = "feature-verify-workflow",
    ownerPackage = "skillbill.workflow.verify",
    contractVersion = "0.1",
    status = RuntimeSurfaceStatus.RESERVED,
    summary = "Feature verification workflow orchestration surface.",
    placeholderReason =
    "Durable workflow state is already persisted through repository ports, but the end-to-end feature-verify " +
      "orchestration contract is still owned by the governed agent skill shell.",
  )
}

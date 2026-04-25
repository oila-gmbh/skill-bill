package skillbill.workflow.implement

import skillbill.contracts.surface.RuntimeSurfaceContract
import skillbill.contracts.surface.RuntimeSurfaceStatus

/** Reserved until feature-implement orchestration moves out of the agent shell. */
object FeatureImplementWorkflowRuntime {
  val contract: RuntimeSurfaceContract = RuntimeSurfaceContract(
    name = "feature-implement-workflow",
    ownerPackage = "skillbill.workflow.implement",
    contractVersion = "0.1",
    status = RuntimeSurfaceStatus.RESERVED,
    summary = "Feature implementation workflow orchestration surface.",
    placeholderReason =
    "Durable workflow state is already persisted through repository ports, but the end-to-end feature-implement " +
      "orchestration contract is still owned by the governed agent skill shell.",
  )
}

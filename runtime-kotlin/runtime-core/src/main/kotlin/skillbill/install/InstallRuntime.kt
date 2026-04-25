package skillbill.install

import skillbill.contracts.surface.RuntimeSurfaceContract
import skillbill.contracts.surface.RuntimeSurfaceStatus

/** Kotlin-owned install primitives used by scaffold and CLI adapter surfaces. */
object InstallRuntime {
  val contract: RuntimeSurfaceContract = RuntimeSurfaceContract(
    name = "install",
    ownerPackage = "skillbill.install",
    contractVersion = "0.1",
    status = RuntimeSurfaceStatus.ACTIVE,
    summary = "Agent-path detection, skill symlink installation, and install rollback primitives.",
    placeholderReason = "",
    supportedOperations = listOf("agent-path", "detect-agents", "link-skill", "rollback-links"),
  )
}

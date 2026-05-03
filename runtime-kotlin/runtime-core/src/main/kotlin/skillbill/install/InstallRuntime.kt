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
    supportedOperations = listOf(
      "agent-path",
      "detect-agents",
      "codex-agents-path",
      "opencode-agents-path",
      "link-skill",
      "cleanup-agent-target",
      "link-codex-agents",
      "unlink-codex-agents",
      "link-opencode-agents",
      "unlink-opencode-agents",
      "rollback-links",
    ),
  )
}

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
      "plan-install",
      "agent-path",
      "detect-agents",
      "codex-agents-path",
      "claude-agents-path",
      "opencode-agents-path",
      "junie-agents-path",
      "link-skill",
      "cleanup-agent-target",
      "link-claude-agents",
      "unlink-claude-agents",
      "link-codex-agents",
      "unlink-codex-agents",
      "link-opencode-agents",
      "unlink-opencode-agents",
      "link-junie-agents",
      "unlink-junie-agents",
      "rollback-links",
    ),
  )
}

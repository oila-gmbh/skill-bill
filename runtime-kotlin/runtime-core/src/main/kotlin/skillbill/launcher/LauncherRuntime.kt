package skillbill.launcher

import skillbill.contracts.surface.RuntimeSurfaceContract
import skillbill.contracts.surface.RuntimeSurfaceStatus

/** Active runtime-selection surface for Python/Kotlin handoff. */
object LauncherRuntime {
  val contract: RuntimeSurfaceContract = RuntimeSurfaceContract(
    name = "launcher",
    ownerPackage = "skillbill.launcher",
    contractVersion = "0.1",
    status = RuntimeSurfaceStatus.ACTIVE,
    summary = "Runtime launcher and Python/Kotlin selection surface.",
    placeholderReason = "",
    supportedOperations = listOf("select-cli-runtime", "python-fallback", "select-mcp-runtime", "mcp-python-fallback"),
  )
}

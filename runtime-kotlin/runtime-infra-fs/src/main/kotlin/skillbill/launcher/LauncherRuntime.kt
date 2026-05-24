package skillbill.launcher

import skillbill.contracts.surface.RuntimeSurfaceContract
import skillbill.contracts.surface.RuntimeSurfaceStatus

/** Active launcher surface for packaged Kotlin runtime entrypoints. */
object LauncherRuntime {
  val contract: RuntimeSurfaceContract = RuntimeSurfaceContract(
    name = "launcher",
    ownerPackage = "skillbill.launcher",
    contractVersion = "0.1",
    status = RuntimeSurfaceStatus.ACTIVE,
    summary = "Runtime launcher for packaged Kotlin CLI and MCP entrypoints.",
    placeholderReason = "",
    supportedOperations = listOf(
      "select-cli-runtime",
      "select-mcp-runtime",
      "register-mcp",
      "unregister-mcp",
    ),
  )
}

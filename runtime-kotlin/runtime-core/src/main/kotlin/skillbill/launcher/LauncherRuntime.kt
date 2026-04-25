package skillbill.launcher

import skillbill.contracts.surface.RuntimeSurfaceContract
import skillbill.contracts.surface.RuntimeSurfaceStatus

/** Reserved until the project needs a Kotlin-owned dual-runtime launcher. */
object LauncherRuntime {
  val contract: RuntimeSurfaceContract = RuntimeSurfaceContract(
    name = "launcher",
    ownerPackage = "skillbill.launcher",
    contractVersion = "0.1",
    status = RuntimeSurfaceStatus.RESERVED,
    summary = "Runtime launcher and runtime-selection surface.",
    placeholderReason =
    "CLI and MCP entry points currently compose the Kotlin runtime directly; launcher behavior should stay " +
      "placeholder-only until release packaging needs a Kotlin-owned Python/Kotlin runtime switch.",
  )
}

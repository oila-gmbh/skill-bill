package skillbill.nativeagent

import skillbill.contracts.surface.RuntimeSurfaceContract
import skillbill.contracts.surface.RuntimeSurfaceStatus

/** Kotlin-owned native-agent rendering, install render, validation, and regenerate surface. */
object NativeAgentRuntime {
  val contract: RuntimeSurfaceContract = RuntimeSurfaceContract(
    name = "native-agent",
    ownerPackage = "skillbill.nativeagent",
    contractVersion = "0.1",
    status = RuntimeSurfaceStatus.ACTIVE,
    summary = "Provider-neutral native agent source parsing, multi-runtime rendering, install render, and validation.",
    placeholderReason = "",
    supportedOperations = listOf(
      "parse-source",
      "render-source",
      "render-install-artifacts",
      "validate-repo",
      "regenerate",
    ),
  )
}

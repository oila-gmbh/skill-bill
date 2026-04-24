package skillbill.scaffold

import skillbill.contracts.surface.RuntimeSurfaceContract
import skillbill.contracts.surface.RuntimeSurfaceStatus

/** Reserved until governed skill scaffolding is intentionally ported. */
object ScaffoldRuntime {
  val contract: RuntimeSurfaceContract = RuntimeSurfaceContract(
    name = "scaffold",
    ownerPackage = "skillbill.scaffold",
    contractVersion = "0.1",
    status = RuntimeSurfaceStatus.RESERVED,
    summary = "Governed skill scaffolding and loader runtime surface.",
    placeholderReason =
    "The Python scaffolder remains canonical for governed skill authoring; Kotlin should expose this surface only " +
      "after scaffold payload validation and atomic file writes are ported together.",
  )
}

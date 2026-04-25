package skillbill.scaffold

import skillbill.contracts.surface.RuntimeSurfaceContract
import skillbill.contracts.surface.RuntimeSurfaceStatus

/** Kotlin-owned governed loader and scaffold mutation surface. */
object ScaffoldRuntime {
  val contract: RuntimeSurfaceContract = RuntimeSurfaceContract(
    name = "scaffold",
    ownerPackage = "skillbill.scaffold",
    contractVersion = "0.1",
    status = RuntimeSurfaceStatus.ACTIVE,
    summary = "Governed loader, scaffold planning, manifest mutation, symlink wiring, and rollback surface.",
    placeholderReason = "",
    supportedOperations = listOf("load-pack", "discover-packs", "discover-addons", "scaffold", "dry-run"),
  )
}

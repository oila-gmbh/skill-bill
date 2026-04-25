package skillbill.install

import skillbill.contracts.surface.RuntimeSurfaceContract
import skillbill.contracts.surface.RuntimeSurfaceStatus

/** Reserved until install and upgrade behavior moves into the Kotlin runtime. */
object InstallRuntime {
  val contract: RuntimeSurfaceContract = RuntimeSurfaceContract(
    name = "install",
    ownerPackage = "skillbill.install",
    contractVersion = "0.1",
    status = RuntimeSurfaceStatus.RESERVED,
    summary = "Install and upgrade runtime surface.",
    placeholderReason =
    "The governed Python installer and existing skill-bill CLI remain the source of truth for install/upgrade " +
      "behavior until the Kotlin runtime owns distribution and agent-config writes.",
  )
}

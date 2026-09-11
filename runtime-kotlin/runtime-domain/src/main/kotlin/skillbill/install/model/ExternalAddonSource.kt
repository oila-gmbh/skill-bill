package skillbill.install.model

import skillbill.model.FileLocation

data class ExternalAddonSource(
  val path: FileLocation,
  val platform: String,
)

data class ExternalAgentAddonSource(
  val path: FileLocation,
)

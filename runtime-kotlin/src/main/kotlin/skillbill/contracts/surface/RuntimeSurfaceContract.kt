package skillbill.contracts.surface

data class RuntimeSurfaceContract(
  val name: String,
  val ownerPackage: String,
  val contractVersion: String,
  val status: RuntimeSurfaceStatus,
  val summary: String,
  val placeholderReason: String,
  val supportedOperations: List<String> = emptyList(),
)

enum class RuntimeSurfaceStatus {
  ACTIVE,
  RESERVED,
}

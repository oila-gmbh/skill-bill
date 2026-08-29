package skillbill.review.context.model

data class ReviewExpansionRecord(
  val expansionId: String,
  val assignmentDigest: String,
  val requestedPath: String,
  val reachabilityReason: String,
  val authorized: Boolean,
  val sequence: Int,
) {
  init {
    require(expansionId.isNotBlank()) { "Expansion id must not be blank." }
    require(assignmentDigest.matches(SHA256_HEX)) { "Expansion assignment digest must be lowercase SHA-256." }
    requireRepositoryRelativePath(requestedPath)
    require(reachabilityReason.isNotBlank()) { "Expansion '$expansionId' must carry a reachability reason." }
    require(sequence >= 0) { "Expansion sequence cannot be negative." }
  }

  val canonical: String
    get() = listOf(
      expansionId,
      assignmentDigest,
      requestedPath,
      reachabilityReason,
      authorized.toString(),
      sequence.toString(),
    ).let { canonicalFields(*it.toTypedArray()) }
}

package skillbill.review.context.model

data class ReviewLaneBundleEntry(val commitSha: String, val orderIndex: Int, val hunkIds: List<String>) {
  init {
    require(commitSha.isNotBlank()) { "Lane bundle entry commit identity must not be blank." }
    require(orderIndex >= 0) { "Lane bundle entry order index cannot be negative." }
    require(hunkIds.isNotEmpty()) { "Lane bundle entry for '$commitSha' must carry at least one hunk." }
    require(hunkIds.distinct().size == hunkIds.size) { "Lane bundle entry hunk ids must be unique." }
  }

  val canonical: String get() = canonicalFields(commitSha, orderIndex, canonicalFields(*hunkIds.toTypedArray()))
}

data class ReviewLaneBundle(val entries: List<ReviewLaneBundleEntry> = emptyList()) {
  init {
    require(entries.map { it.commitSha }.distinct().size == entries.size) {
      "Lane bundle must carry one entry per commit."
    }
    require(entries.map { it.orderIndex }.zipWithNext().all { (previous, next) -> previous < next }) {
      "Lane bundle entries must preserve packet commit order."
    }
    val ids = entries.flatMap { it.hunkIds }
    require(ids.distinct().size == ids.size) { "Lane bundle claims a hunk id under more than one commit." }
  }

  val hunkIds: List<String> get() = entries.flatMap { it.hunkIds }
  val canonical: String get() = canonicalFields(*entries.map { it.canonical }.toTypedArray())
  val bundleDigest: String get() = sha256(canonical)

  companion object {
    val EMPTY: ReviewLaneBundle = ReviewLaneBundle()
  }
}

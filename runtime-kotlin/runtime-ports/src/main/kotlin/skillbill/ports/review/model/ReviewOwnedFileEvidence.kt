package skillbill.ports.review.model

data class ReviewOwnedFileEvidence(val path: String, val changedContent: String) {
  init {
    require(path.isNotBlank() && !path.startsWith('/'))
  }
}

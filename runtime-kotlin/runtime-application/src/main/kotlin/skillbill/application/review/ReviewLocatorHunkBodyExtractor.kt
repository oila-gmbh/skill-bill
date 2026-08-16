package skillbill.application.review

import skillbill.ports.review.ReviewStoredHunkBodyExtractor
import skillbill.review.context.model.ReviewChangedHunk

object ReviewLocatorHunkBodyExtractor : ReviewStoredHunkBodyExtractor {
  override fun extract(payload: String, hunk: ReviewChangedHunk): String =
    ReviewHunkStoreIndexing.extractStoredBody(hunk, payload, hunk.evidenceLocator.storePath)
}

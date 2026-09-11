package skillbill.infrastructure.fs

import skillbill.ports.review.ReviewStoredHunkBodyExtractor
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewExpansionRecord
import skillbill.review.context.model.ReviewLaneIdentity
import skillbill.review.context.model.ReviewOperationPolicy
import java.nio.file.Path

internal data class FileSystemReviewEvidenceBrokerReadStateInit(
  val root: Path,
  val assignment: ReviewAssignment,
  val budget: ReviewContextBudgetPolicy,
  val identity: ReviewLaneIdentity,
  val policy: ReviewOperationPolicy,
  val authorizedExpansionLedger: List<ReviewExpansionRecord>,
  val projectedHunks: List<ReviewChangedHunk>,
  val locatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort,
  val bodyExtractor: ReviewStoredHunkBodyExtractor,
  val completeFileCheckpoint: Map<String, String?>,
  val hunkCommitById: Map<String, String>,
)

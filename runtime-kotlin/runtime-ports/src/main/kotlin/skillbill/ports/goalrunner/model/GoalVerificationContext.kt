package skillbill.ports.goalrunner.model

import skillbill.contracts.goalplanning.GoalVerificationBoundaryCaps

object GoalVerificationContext {
  val MAX_DISCOVERY_FILE_COUNT: Int get() = GoalVerificationBoundaryCaps.maxDiscoveryFileCount
  val MAX_HEADINGS_PER_FILE: Int get() = GoalVerificationBoundaryCaps.maxHeadingsPerFile
  val MAX_CATALOG_HEADINGS: Int get() = GoalVerificationBoundaryCaps.maxCatalogHeadings
  val HISTORY_RECENCY_DAYS: Int get() = GoalVerificationBoundaryCaps.historyRecencyDays
  val MAX_SELECTED_BODIES: Int get() = GoalVerificationBoundaryCaps.maxSelectedBodies
  val MAX_BODY_BYTES: Int get() = GoalVerificationBoundaryCaps.maxBodyBytes
  val MAX_TOTAL_BODY_BYTES: Int get() = GoalVerificationBoundaryCaps.maxTotalBodyBytes
  val MAX_BOUNDARY_FILE_BYTES: Long get() = GoalVerificationBoundaryCaps.maxBoundaryFileBytes
}

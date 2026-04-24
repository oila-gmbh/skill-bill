package skillbill

import skillbill.db.DatabaseRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

const val SAMPLE_REVIEW: String =
  """
  Routed to: bill-kotlin-code-review
  Review session ID: rvs-20260402-001
  Review run ID: rvw-20260402-001
  Detected review scope: unstaged changes
  Detected stack: kotlin
  Execution mode: inline
  Specialist reviews: architecture, testing, architecture

  ### 2. Risk Register
  - [F-001] Major | High | README.md:12 | README wording is stale after the routing change.
  - [F-002] Minor | Medium | install.sh:88 | Installer prompt wording is inconsistent with the new flow.
  """

const val TABLE_REVIEW: String =
  """
  Routed to: bill-kmp-code-review
  Review session ID: rvs-20260402-tbl-a
  Review run ID: rvw-20260402-tbl-a
  Detected review scope: files
  Detected stack: kmp
  Execution mode: inline

  ## Section 2 — Risk Register

  | # | Severity | Category | File | Line(s) | Finding |
  |---|----------|----------|------|---------|---------|
  | 1 | High | Correctness | ViewModel.kt | 147-152 | init block calls refresh() |
  | 2 | Medium | UI | Screen.kt | 156 | Loading indicator not centered |
  """

fun tempDbConnection(prefix: String): Pair<Path, Connection> {
  val tempDir = Files.createTempDirectory(prefix)
  val dbPath = tempDir.resolve("metrics.db")
  return dbPath to DatabaseRuntime.ensureDatabase(dbPath)
}

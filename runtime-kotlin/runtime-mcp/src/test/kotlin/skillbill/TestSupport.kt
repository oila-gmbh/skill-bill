package skillbill

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

const val ZERO_FINDING_REVIEW: String =
  """
  Routed to: bill-kmp-code-review
  Review session ID: rvs-20260427-empty
  Review run ID: rvw-20260427-empty
  Detected review scope: unstaged changes
  Detected stack: kmp
  Execution mode: inline

  ### 2. Risk Register
  No findings.
  """

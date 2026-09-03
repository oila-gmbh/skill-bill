package skillbill.contracts.issuekey

const val TRACKER_STYLE_ISSUE_KEY_PATTERN: String = "[A-Z0-9]+-\\d+(?:\\.\\d+)?"

val TRACKER_STYLE_ISSUE_KEY: Regex = Regex("(?i)$TRACKER_STYLE_ISSUE_KEY_PATTERN")

val ISSUE_AND_FEATURE_DIRECTORY: Regex = Regex("^(?i)($TRACKER_STYLE_ISSUE_KEY_PATTERN)-(.+)$")

fun issueAndFeature(directoryName: String): Pair<String, String> {
  val match = ISSUE_AND_FEATURE_DIRECTORY.matchEntire(directoryName)
  if (match != null) {
    return match.groupValues[1].uppercase() to match.groupValues[2]
  }
  val parts = directoryName.split("-", limit = 2)
  return parts.first() to parts.getOrElse(1) { "decomposition" }
}

fun issueKeyFromBranch(branchName: String): String? =
  TRACKER_STYLE_ISSUE_KEY.find(branchName)?.value?.uppercase()

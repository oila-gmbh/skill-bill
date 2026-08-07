package skillbill.application.work

/**
 * SKILL-167 follow-up: the IDE status surface reports work for the checked-out branch,
 * not the whole repository. Feature branches carry their issue key by construction
 * (`feat/SKILL-167-...`), so scoping matches candidate issue keys against the branch
 * name instead of requiring a durable branch column on every work row.
 */
internal object IdeStatusBranchScope {

  /**
   * Case-insensitive whole-token containment: `SKILL-16` must not match
   * `feat/skill-167-x`, so both neighbors of a hit must be non-alphanumeric.
   */
  fun branchReferencesIssueKey(branch: String, issueKey: String): Boolean {
    val haystack = branch.lowercase()
    val needle = issueKey.trim().lowercase()
    if (needle.isEmpty()) return false
    var index = haystack.indexOf(needle)
    while (index >= 0) {
      val before = haystack.getOrNull(index - 1)
      val after = haystack.getOrNull(index + needle.length)
      if (before?.isLetterOrDigit() != true && after?.isLetterOrDigit() != true) return true
      index = haystack.indexOf(needle, index + 1)
    }
    return false
  }
}

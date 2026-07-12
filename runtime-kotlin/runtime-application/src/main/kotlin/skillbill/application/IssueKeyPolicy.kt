package skillbill.application

internal fun normalizeIssueKey(issueKey: String?): String? = issueKey?.trim()?.also {
  require(it.isNotEmpty()) { "issue key cannot be blank." }
  require(it.length <= MAX_ISSUE_KEY_LENGTH) { "issue key must be at most $MAX_ISSUE_KEY_LENGTH characters." }
  require(it.none(Character::isISOControl)) { "issue key cannot contain control characters." }
}

internal fun normalizeRequiredIssueKey(issueKey: String): String = requireNotNull(normalizeIssueKey(issueKey))

private const val MAX_ISSUE_KEY_LENGTH = 128

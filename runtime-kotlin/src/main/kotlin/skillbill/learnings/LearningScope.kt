package skillbill.learnings

enum class LearningScope(
  val wireName: String,
  private val requiresScopeKey: Boolean,
) {
  GLOBAL("global", false),
  REPO("repo", true),
  SKILL("skill", true),
  ;

  fun normalizeScopeKey(scopeKey: String): String {
    val normalizedScopeKey = scopeKey.trim()
    if (!requiresScopeKey) {
      return ""
    }
    require(normalizedScopeKey.isNotEmpty()) {
      "Learning scope '$wireName' requires a non-empty --scope-key."
    }
    return normalizedScopeKey
  }

  companion object {
    val precedence: List<LearningScope> = listOf(SKILL, REPO, GLOBAL)

    fun fromWireName(rawValue: String): LearningScope = entries.firstOrNull {
      it.wireName == rawValue.trim()
    } ?: throw IllegalArgumentException(
      "Learning scope must be one of ${entries.joinToString(", ") { it.wireName }}.",
    )

    fun fromWireNameOrNull(rawValue: String?): LearningScope? = entries.firstOrNull {
      it.wireName == rawValue?.trim()
    }

    fun wireNames(): List<String> = entries.map { it.wireName }

    fun precedenceWireNames(): List<String> = precedence.map { it.wireName }

    fun emptyScopeCounts(): LinkedHashMap<String, Int> = linkedMapOf<String, Int>().apply {
      LearningScope.entries.forEach { scope -> put(scope.wireName, 0) }
    }
  }
}

package skillbill.application.model

data class Semver(
  val major: Int,
  val minor: Int,
  val patch: Int,
  val prerelease: List<String> = emptyList(),
) : Comparable<Semver> {
  val isPrerelease: Boolean = prerelease.isNotEmpty()

  override fun compareTo(other: Semver): Int = compareValuesBy(this, other, Semver::major, Semver::minor, Semver::patch)
    .takeIf { it != 0 }
    ?: comparePrerelease(prerelease, other.prerelease)

  companion object {
    private val pattern = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$""")

    fun parse(value: String): Semver? {
      val match = pattern.matchEntire(value.trim()) ?: return null
      val prerelease = match.groupValues[PRERELEASE_GROUP]
        .takeIf(String::isNotBlank)
        ?.split(".")
        .orEmpty()
      val core = listOf(MAJOR_GROUP, MINOR_GROUP, PATCH_GROUP)
        .map { group -> match.groupValues[group].toIntOrNull() }
      return core
        .takeUnless { numbers -> numbers.any { number -> number == null } }
        ?.takeUnless { prerelease.any(String::isBlank) }
        ?.let { numbers ->
          Semver(
            major = numbers[MAJOR_INDEX] ?: 0,
            minor = numbers[MINOR_INDEX] ?: 0,
            patch = numbers[PATCH_INDEX] ?: 0,
            prerelease = prerelease,
          )
        }
    }

    private const val MAJOR_GROUP = 1
    private const val MINOR_GROUP = 2
    private const val PATCH_GROUP = 3
    private const val PRERELEASE_GROUP = 4
    private const val MAJOR_INDEX = 0
    private const val MINOR_INDEX = 1
    private const val PATCH_INDEX = 2
  }
}

private fun comparePrerelease(left: List<String>, right: List<String>): Int {
  val identifierComparison = left.zip(right)
    .map { (leftPart, rightPart) -> comparePrereleaseIdentifier(leftPart, rightPart) }
    .firstOrNull { compared -> compared != 0 }
  return when {
    identifierComparison != null -> identifierComparison
    left.isEmpty() && right.isEmpty() -> 0
    left.isEmpty() -> 1
    right.isEmpty() -> -1
    else -> left.size.compareTo(right.size)
  }
}

private fun comparePrereleaseIdentifier(left: String, right: String): Int {
  val leftNumeric = left.toLongOrNull()
  val rightNumeric = right.toLongOrNull()
  return when {
    leftNumeric != null && rightNumeric != null -> leftNumeric.compareTo(rightNumeric)
    leftNumeric != null -> -1
    rightNumeric != null -> 1
    else -> left.compareTo(right)
  }
}

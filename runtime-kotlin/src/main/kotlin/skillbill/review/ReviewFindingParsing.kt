package skillbill.review

fun requireMatch(pattern: Regex, text: String, errorMessage: String): String =
  pattern.find(text)?.groups?.get("value")?.value ?: throw IllegalArgumentException(errorMessage)

fun parseReviewFindings(text: String): List<ImportedFinding> {
  val bulletFindings = parseBulletFindings(text)
  return if (bulletFindings.isNotEmpty()) bulletFindings else parseTableFindings(text)
}

fun parseBulletFindings(text: String): List<ImportedFinding> {
  val seenIds = mutableSetOf<String>()
  return findingPattern.findAll(text).map { match ->
    val findingId = match.groups["findingId"]?.value.orEmpty()
    require(seenIds.add(findingId)) {
      "Review output contains duplicate finding id '$findingId'."
    }
    ImportedFinding(
      findingId = findingId,
      severity = match.groups["severity"]?.value.orEmpty(),
      confidence = match.groups["confidenceLevel"]?.value.orEmpty(),
      location = match.groups["location"]?.value.orEmpty().trim(),
      description = match.groups["description"]?.value.orEmpty().trim(),
      findingText = match.value.trim(),
    )
  }.toList()
}

fun parseTableFindings(text: String): List<ImportedFinding> {
  val lines = text.lines()
  val headerIndex = lines.indexOfFirst(::isTableHeaderLine)
  val columnMap =
    if (headerIndex >= 0) {
      buildColumnMap(lines[headerIndex].split("|").map { it.trim() })
    } else {
      emptyMap()
    }
  return if (headerIndex < 0 || !setOf("number", "severity", "description").all(columnMap::containsKey)) {
    emptyList()
  } else {
    parseTableBody(lines.drop(headerIndex + 1), columnMap)
  }
}

fun extractSummaryValue(text: String, key: String): String? =
  summaryPatterns[key]?.find(text)?.groups?.get("value")?.value?.trim()

fun extractSpecialistReviews(text: String): List<String> {
  val seen = linkedSetOf<String>()
  specialistReviewsPattern.findAll(text).forEach { match ->
    match
      .groups["value"]
      ?.value
      .orEmpty()
      .split(",")
      .map(String::trim)
      .filter(String::isNotEmpty)
      .forEach(seen::add)
  }
  return seen.toList()
}

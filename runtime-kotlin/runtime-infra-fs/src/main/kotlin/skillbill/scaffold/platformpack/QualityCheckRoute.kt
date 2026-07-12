package skillbill.scaffold.platformpack

import skillbill.scaffold.validation.parseSkillFrontmatter
import java.nio.file.Files
import java.nio.file.Path

data class QualityCheckRoute(
  val detectedStack: String,
  val routedSkill: String,
  val matchedSignals: List<String>,
  val fallback: Boolean = false,
  val fallbackReason: String? = null,
)

fun routeQualityCheck(repoRoot: Path, routingEvidence: Collection<String>): QualityCheckRoute? {
  val packs = discoverPlatformPacks(repoRoot.toAbsolutePath().normalize().resolve("platform-packs"))
  val scored = packs.map { pack ->
    val matched = pack.routingSignals.strong.filter { signal ->
      routingEvidence.any { evidence -> routingSignalMatches(evidence, signal) }
    }
    Triple(pack, matched.size, matched)
  }.filter { (_, score) -> score > 0 }
  if (scored.isEmpty()) return null
  val maximum = scored.maxOf { (_, score) -> score }
  val winners = scored.filter { (_, score) -> score == maximum }
  require(winners.size == 1) {
    val slugs = winners.map { (pack) -> pack.slug }.sorted()
    "Quality-check routing is ambiguous for $slugs; provide stronger manifest-declared evidence."
  }
  val (pack, _, matchedSignals) = winners.single()
  val content = loadQualityCheckContent(pack)
  val routedSkill = parseSkillFrontmatter(Files.readString(content))["name"].orEmpty()
  require(routedSkill.isNotBlank()) { "Quality-check content '$content' has no routed skill name." }
  return QualityCheckRoute(pack.slug, routedSkill, matchedSignals.sorted())
}

private fun routingSignalMatches(evidence: String, signal: String): Boolean {
  val normalizedEvidence = evidence.lowercase()
  val normalizedSignal = signal.lowercase()
  if ('*' !in normalizedSignal) return normalizedSignal in normalizedEvidence
  val pattern = normalizedSignal.split('*').joinToString(".*") { part -> Regex.escape(part) }
  val regex = Regex("^$pattern$")
  return regex.matches(normalizedEvidence) || regex.matches(normalizedEvidence.substringAfterLast('/'))
}

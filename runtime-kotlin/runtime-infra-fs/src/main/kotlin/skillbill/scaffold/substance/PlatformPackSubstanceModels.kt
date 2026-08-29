package skillbill.scaffold.substance

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path

const val PLATFORM_PACK_SUBSTANCE_CONTRACT_VERSION = "0.1"

private const val DEFAULT_MINIMUM_RULES = 10
private const val DEFAULT_MINIMUM_CLUSTERS = 3
private const val DEFAULT_MINIMUM_QUALITY_FACETS = 7
private const val DEFAULT_SHARED_SHINGLE_PERCENT = 35
private const val DEFAULT_PAIR_SIMILARITY_PERCENT = 65
private const val PERCENT_SCALE = 100
private const val PERCENTAGE_DECIMAL_PLACES = 2

data class SubstancePolicy(
  val minimumRules: Int = DEFAULT_MINIMUM_RULES,
  val minimumClusters: Int = DEFAULT_MINIMUM_CLUSTERS,
  val minimumQualityFacets: Int = DEFAULT_MINIMUM_QUALITY_FACETS,
  val maximumSharedShingles: Fraction = Fraction(DEFAULT_SHARED_SHINGLE_PERCENT, PERCENT_SCALE),
  val maximumPairSimilarity: Fraction = Fraction(DEFAULT_PAIR_SIMILARITY_PERCENT, PERCENT_SCALE),
)

data class Fraction(val numerator: Int, val denominator: Int) : Comparable<Fraction> {
  override fun compareTo(other: Fraction): Int =
    numerator.toLong() * other.denominator compareTo other.numerator.toLong() * denominator

  fun percentage(): String = BigDecimal(numerator).multiply(BigDecimal(PERCENT_SCALE))
    .divide(BigDecimal(denominator), PERCENTAGE_DECIMAL_PLACES, RoundingMode.HALF_UP).toPlainString() + "%"
}

internal enum class AuthoredFileRole { BASELINE, QUALITY_CHECK, SPECIALIST, SIDECAR }

internal data class AuthoredFile(
  val pack: String,
  val role: AuthoredFileRole,
  val area: String?,
  val path: Path,
  val shingles: Set<String>,
)

data class SpecialistMetric(
  val pack: String,
  val area: String,
  val file: String,
  val inherited: Boolean,
  val substantiveRules: Int,
  val failureModeClusters: Int,
  val concreteEvidenceRules: Int,
  val placeholders: List<String>,
)

data class SimilarityPair(val role: String, val firstFile: String, val secondFile: String, val similarity: Fraction)

data class PackMetric(
  val pack: String,
  val physicalAreas: List<String>,
  val inheritedAreas: List<String>,
  val specialists: List<SpecialistMetric>,
  val qualityCheckFile: String?,
  val qualityCheckSections: List<String>,
  val qualityCheckFacets: List<String>,
  val sharedShingles: Fraction,
  val highestCorrespondingSimilarity: SimilarityPair?,
)

data class SubstanceViolation(
  val id: String,
  val pack: String,
  val areaOrRole: String,
  val files: List<String>,
  val measured: String,
  val target: String,
  val rule: String,
) {
  fun format(): String = buildString {
    append("platform pack substance [$id] pack=$pack role=$areaOrRole files=${files.joinToString(",")}")
    append(" measured=$measured required=$target: $rule")
  }
}

data class PlatformPackSubstanceReport(
  val contractVersion: String,
  val packs: List<PackMetric>,
  val pairs: List<SimilarityPair>,
  val violations: List<SubstanceViolation>,
  val auditErrors: List<String>,
)

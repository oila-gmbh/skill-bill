package skillbill.scaffold.substance

import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.platformpack.CODE_REVIEW_FALLBACK_CAPABILITY
import skillbill.scaffold.platformpack.loadPlatformManifest
import skillbill.scaffold.policy.scaffold.APPROVED_CODE_REVIEW_AREAS
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import java.util.Locale
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo


const val PLATFORM_PACK_SUBSTANCE_CONTRACT_VERSION = "0.1"

data class SubstancePolicy(
  val minimumRules: Int = 10,
  val minimumClusters: Int = 3,
  val minimumQualityFacets: Int = 7,
  val maximumSharedShingles: Fraction = Fraction(35, 100),
  val maximumPairSimilarity: Fraction = Fraction(65, 100),
)

data class Fraction(val numerator: Int, val denominator: Int) : Comparable<Fraction> {
  override fun compareTo(other: Fraction): Int =
    numerator.toLong() * other.denominator compareTo other.numerator.toLong() * denominator

  fun percentage(): String = BigDecimal(numerator).multiply(BigDecimal(100))
    .divide(BigDecimal(denominator), 2, RoundingMode.HALF_UP).toPlainString() + "%"
}

internal enum class AuthoredFileRole { BASELINE, QUALITY_CHECK, SPECIALIST, SIDECAR }

internal data class AuthoredFile(val pack: String, val role: AuthoredFileRole, val area: String?, val path: Path, val shingles: Set<String>)

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


@file:Suppress(
  "MagicNumber",
  "TooManyFunctions",
  "LongMethod",
  "MaxLineLength",
  "LongParameterList",
  "CyclomaticComplexMethod",
  "ktlint:standard:max-line-length",
)

package skillbill.scaffold.substance

import org.yaml.snakeyaml.Yaml
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.platformpack.loadPlatformManifest
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
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

enum class AuthoredFileRole { BASELINE, QUALITY_CHECK, SPECIALIST, SIDECAR }

data class AuthoredFile(val pack: String, val role: AuthoredFileRole, val area: String?, val path: Path, val shingles: Set<String>)

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
  val acknowledgedBy: String? = null,
) {
  fun format(): String = buildString {
    append("platform pack substance [$id] pack=$pack role=$areaOrRole files=${files.joinToString(",")}")
    append(" measured=$measured required=$target: $rule")
    acknowledgedBy?.let { append(" (temporary baseline $it)") }
  }
}

data class BaselineAcknowledgement(val id: String, val measured: String, val target: String, val owner: String)

data class PlatformPackSubstanceReport(
  val contractVersion: String,
  val packs: List<PackMetric>,
  val pairs: List<SimilarityPair>,
  val violations: List<SubstanceViolation>,
  val auditErrors: List<String>,
  val baselineErrors: List<String>,
) {
  val blockingViolations: List<SubstanceViolation> = violations.filter { it.acknowledgedBy == null }
}

object PlatformPackSubstanceAudit {
  fun audit(repoRoot: Path, policy: SubstancePolicy = SubstancePolicy()): PlatformPackSubstanceReport {
    val root = repoRoot.toAbsolutePath().normalize()
    val (discoveredManifests, manifestErrors) = discoverManifests(root)
    val (manifests, contentErrors) = retainManifestsWithReadableDeclaredContent(root, discoveredManifests)
    val auditErrors = (manifestErrors + contentErrors).sorted()
    val manifestsBySlug = manifests.associateBy { it.slug }
    val effectiveAreas = manifests.associate {
      it.slug to effectiveAreas(it.slug, manifestsBySlug)
    }
    val files = manifests.flatMap(::authoredFiles).sortedBy { it.path.toString() }
    val pairs = correspondingPairs(files)
    val rawViolations = mutableListOf<SubstanceViolation>()
    val metrics = manifests.map { pack ->
      rawViolations += compositionViolations(pack, manifestsBySlug)
      val packFiles = files.filter { it.pack == pack.slug }
      val specialists = specialistMetrics(root, pack, effectiveAreas, manifestsBySlug)
      val missingAreas = APPROVED_CODE_REVIEW_AREAS - effectiveAreas.getValue(pack.slug)
      if (missingAreas.isNotEmpty()) {
        rawViolations += packViolation(
          pack.slug,
          "effective-area-coverage",
          listOf("platform-packs/${pack.slug}/platform.yaml"),
          missingAreas.sorted().joinToString(","),
          "all-approved-areas",
          "maintained pack must effectively cover every approved review area",
        )
      }
      specialists.filterNot { it.inherited }.forEach { metric ->
        if (metric.substantiveRules < policy.minimumRules) {
          rawViolations += violation(
            metric,
            "rules",
            metric.substantiveRules.toString(),
            ">=${policy.minimumRules}",
            "physical specialist requires substantive enforceable rules",
          )
        }
        if (metric.failureModeClusters < policy.minimumClusters) {
          rawViolations += violation(
            metric,
            "clusters",
            metric.failureModeClusters.toString(),
            "${policy.minimumClusters}",
            "physical specialist must represent all failure-mode clusters",
          )
        }
        if (metric.placeholders.isNotEmpty()) {
          rawViolations += violation(
            metric,
            "placeholders",
            metric.placeholders.joinToString("|"),
            "none",
            "forbidden placeholder content is not substantive",
          )
        }
      }
      val qualityPath = resolveQualityCheck(pack.slug, manifestsBySlug)
      val qualityText = qualityPath?.let(Files::readString)
      val sections = qualityText?.let(::qualitySections).orEmpty()
      val facets = qualityText?.let(::qualityFacets).orEmpty()
      if (qualityPath == null) {
        rawViolations += packViolation(
          pack.slug,
          "quality-check",
          emptyList(),
          "absent",
          "present",
          "maintained pack must declare a quality checker",
        )
      } else {
        if (sections.size < REQUIRED_QUALITY_SECTIONS.size) {
          rawViolations += packViolation(
            pack.slug,
            "quality-check-sections",
            listOf(qualityPath.relativeTo(root).toString()),
            sections.joinToString(",").ifEmpty { "none" },
            REQUIRED_QUALITY_SECTIONS.joinToString(","),
            "quality checker must contain every governed section",
          )
        }
        if (facets.size < policy.minimumQualityFacets) {
          rawViolations += packViolation(
            pack.slug,
            "quality-check",
            listOf(qualityPath.relativeTo(root).toString()),
            facets.size.toString(),
            "${policy.minimumQualityFacets}",
            "quality checker must cover every depth facet",
          )
        }
      }
      val packShingles = packFiles.flatMap { it.shingles }.toSet()
      val otherShingles = files.filter { it.pack != pack.slug }.flatMap { it.shingles }.toSet()
      val shared = fraction(packShingles.count { it in otherShingles }, packShingles.size)
      if (shared > policy.maximumSharedShingles) {
        rawViolations += packViolation(
          pack.slug,
          "shared-shingles",
          packFiles.map {
            it.path.relativeTo(root).toString()
          },
          shared.percentage(),
          "<=35.00%",
          "pack exceeds shared normalized five-word sequence threshold",
        )
      }
      val packPairs = pairs.filter { pair ->
        pair.firstFile.startsWith(
          "platform-packs/${pack.slug}/",
        ) || pair.secondFile.startsWith("platform-packs/${pack.slug}/")
      }
      packPairs.filter { it.similarity > policy.maximumPairSimilarity }.forEach { pair ->
        rawViolations += packViolation(
          pack.slug,
          "pair:${pair.role}",
          listOf(pair.firstFile, pair.secondFile),
          pair.similarity.percentage(),
          "<=65.00%",
          "corresponding authored rubrics exceed similarity threshold",
        )
      }
      PackMetric(
        pack.slug,
        pack.declaredCodeReviewAreas.sorted(),
        (
          effectiveAreas.getValue(
            pack.slug,
          ) - pack.declaredCodeReviewAreas.toSet()
          ).sorted(),
        specialists,
        qualityPath?.relativeTo(
          root,
        )?.toString(),
        sections,
        facets,
        shared,
        packPairs.maxByOrNull {
          it.similarity
        },
      )
    }
    val (acknowledgements, parseErrors) = readBaseline(root)
    val duplicateIds = acknowledgements.groupBy { it.id }.filterValues { it.size > 1 }.keys
    val rawById = rawViolations.associateBy { it.id }
    val baselineErrors = parseErrors.toMutableList()
    duplicateIds.forEach { baselineErrors += "duplicate baseline identity '$it'" }
    acknowledgements.forEach { baseline ->
      val violation = rawById[baseline.id]
      if (violation == null) {
        baselineErrors += "stale or unknown baseline identity '${baseline.id}'"
      } else if (violation.measured != baseline.measured || violation.target != baseline.target) {
        baselineErrors += "baseline '${baseline.id}' drifted: expected ${baseline.measured}/${baseline.target}, measured ${violation.measured}/${violation.target}"
      }
    }
    val valid = acknowledgements.filter { it.id !in duplicateIds }.associateBy { it.id }
    val violations = rawViolations.map { violation ->
      val baseline = valid[violation.id]?.takeIf { it.measured == violation.measured && it.target == violation.target }
      if (baseline == null) violation else violation.copy(acknowledgedBy = baseline.owner)
    }.sortedBy { it.id }
    return PlatformPackSubstanceReport(
      PLATFORM_PACK_SUBSTANCE_CONTRACT_VERSION,
      metrics.sortedBy {
        it.pack
      },
      pairs,
      violations,
      auditErrors,
      baselineErrors.sorted(),
    )
  }

  fun normalize(text: String, names: Collection<String> = emptyList()): List<String> {
    var normalized = text.replace("\r\n", "\n").replace(Regex("(?s)\\A---\\n.*?\\n---\\n"), "")
    normalized = normalized.replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
    normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    names.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach { name ->
      val lexicalName = Regex.escape(name.lowercase(Locale.ROOT))
      normalized = normalized.replace(
        Regex("(?<![\\p{L}\\p{N}_])$lexicalName(?![\\p{L}\\p{N}_])"),
        rolePlaceholder(name),
      )
    }
    return normalized.replace(Regex("[`*_~>#|{}\\[\\]()]"), " ")
      .replace(Regex("[^\\p{L}\\p{N}./:_+-]+"), " ").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
  }

  fun shingles(tokens: List<String>): Set<String> = if (tokens.size < 5) {
    emptySet()
  } else {
    tokens.windowed(5).map {
      it.joinToString(" ")
    }.toSet()
  }

  private fun discoverManifests(root: Path): Pair<List<PlatformManifest>, List<String>> {
    val packsRoot = root.resolve("platform-packs")
    if (!packsRoot.isDirectory()) return emptyList<PlatformManifest>() to emptyList()
    val errors = mutableListOf<String>()
    val manifests = Files.list(packsRoot).use { stream ->
      stream.filter {
        it.isDirectory() && !it.name.startsWith(".") && it.resolve("platform.yaml").isRegularFile()
      }.sorted().map { packRoot ->
        runCatching { loadPlatformManifest(packRoot) }.getOrElse { error ->
          errors += "platform-packs/${packRoot.name}/platform.yaml: ${error.message ?: error::class.simpleName}"
          null
        }
      }.filter { it != null }.map { it!! }.toList()
    }
    return manifests to errors.sorted()
  }

  private fun retainManifestsWithReadableDeclaredContent(
    root: Path,
    manifests: List<PlatformManifest>,
  ): Pair<List<PlatformManifest>, List<String>> {
    val errors = mutableListOf<String>()
    val readable = manifests.filter { pack ->
      declaredContentPaths(pack).mapNotNull { path ->
        runCatching { Files.readString(path) }.exceptionOrNull()?.let { error ->
          val source = path.toAbsolutePath().normalize().relativeTo(root).toString()
          "$source: declared authored content is missing or unreadable (${error::class.simpleName}: ${error.message})"
        }
      }.also(errors::addAll).isEmpty()
    }
    return readable to errors.sorted()
  }

  private fun declaredContentPaths(pack: PlatformManifest): List<Path> = buildList {
    pack.declaredFiles.baseline?.let(::add)
    addAll(pack.declaredFiles.areas.values)
    pack.declaredQualityCheckFile?.let(::add)
  }.distinct().sortedBy(Path::toString)

  private fun authoredFiles(pack: PlatformManifest): List<AuthoredFile> {
    val declared = buildList {
      pack.declaredFiles.baseline?.let { add(Triple(AuthoredFileRole.BASELINE, null, it)) }
      pack.declaredFiles.areas.forEach { (area, path) -> add(Triple(AuthoredFileRole.SPECIALIST, area, path)) }
      pack.declaredQualityCheckFile?.let { add(Triple(AuthoredFileRole.QUALITY_CHECK, null, it)) }
    }
    return declared.flatMap { (role, area, path) ->
      val names = listOf(pack.slug, pack.displayName.orEmpty(), path.parent.name)
      val primary = AuthoredFile(pack.slug, role, area, path, shingles(normalize(Files.readString(path), names)))
      val sidecars = if (role == AuthoredFileRole.SPECIALIST) {
        linkedSidecars(
          path,
        ).map { sidecar ->
          AuthoredFile(
            pack.slug,
            AuthoredFileRole.SIDECAR,
            area,
            sidecar,
            shingles(normalize(Files.readString(sidecar), names)),
          )
        }
      } else {
        emptyList()
      }
      listOf(primary) + sidecars
    }
  }

  private fun linkedSidecars(content: Path): List<Path> = Regex(
    "\\[[^]]+]\\(([^)]+\\.md)\\)",
  ).findAll(Files.readString(content)).mapNotNull { match ->
    val candidate = content.parent.resolve(match.groupValues[1]).normalize()
    candidate.takeIf { it.parent == content.parent && it.isRegularFile() && it.name !in GENERATED_POINTER_NAMES }
  }.distinct().sortedBy { it.toString() }.toList()

  private fun effectiveAreas(
    slug: String,
    packs: Map<String, PlatformManifest>,
    visiting: Set<String> = emptySet(),
  ): Set<String> = resolveEffectiveAreas(slug, packs, visiting).areas

  private data class AreaResolution(val areas: Set<String>, val valid: Boolean)

  private fun resolveEffectiveAreas(
    slug: String,
    packs: Map<String, PlatformManifest>,
    visiting: Set<String> = emptySet(),
  ): AreaResolution {
    if (slug in visiting) return AreaResolution(emptySet(), false)
    val pack = packs[slug] ?: return AreaResolution(emptySet(), false)
    var valid = true
    val inherited = pack.codeReviewComposition?.baselineLayers.orEmpty().filter { it.required }.flatMap { layer ->
      val target = packs[layer.platform]
      if (target?.routedSkillName != layer.skill) {
        valid = false
        emptySet()
      } else {
        val resolved = resolveEffectiveAreas(layer.platform, packs, visiting + slug)
        valid = valid && resolved.valid
        if (resolved.valid) resolved.areas else emptySet()
      }
    }.toSet()
    return AreaResolution(pack.declaredCodeReviewAreas.toSet() + inherited, valid)
  }

  private fun compositionViolations(
    pack: PlatformManifest,
    packs: Map<String, PlatformManifest>,
  ): List<SubstanceViolation> =
    pack.codeReviewComposition?.baselineLayers.orEmpty().filter { it.required }.mapNotNull { layer ->
      val target = packs[layer.platform]
      val measured = when {
        target == null -> "missing-pack:${layer.platform}"
        target.routedSkillName != layer.skill -> "mismatched-skill:${target.routedSkillName ?: "absent"}"
        hasRequiredCycle(layer.platform, packs, setOf(pack.slug)) -> "cyclic-required-composition"
        else -> return@mapNotNull null
      }
      packViolation(
        pack.slug,
        "composition:${layer.platform}",
        listOf("platform-packs/${pack.slug}/platform.yaml"),
        measured,
        "valid-required-layer:${layer.skill}",
        "required composition must resolve acyclically to the target pack's declared baseline",
      )
    }

  private fun hasRequiredCycle(
    slug: String,
    packs: Map<String, PlatformManifest>,
    visiting: Set<String> = emptySet(),
  ): Boolean {
    if (slug in visiting) return true
    val pack = packs[slug] ?: return false
    return pack.codeReviewComposition?.baselineLayers.orEmpty().filter { it.required }.any { layer ->
      hasRequiredCycle(layer.platform, packs, visiting + slug)
    }
  }

  private fun specialistMetrics(
    root: Path,
    pack: PlatformManifest,
    effective: Map<String, Set<String>>,
    packs: Map<String, PlatformManifest>,
  ): List<SpecialistMetric> {
    val physical = pack.declaredFiles.areas.map { (area, path) ->
      metric(
        root,
        pack.slug,
        area,
        path,
        inherited = false,
      )
    }
    val inherited = (effective.getValue(pack.slug) - pack.declaredCodeReviewAreas.toSet()).map { area ->
      val source = findAreaSource(
        pack.slug,
        area,
        packs,
      ) ?: return@map SpecialistMetric(pack.slug, area, "absent", true, 0, 0, 0, emptyList())
      metric(root, pack.slug, area, source, inherited = true)
    }
    return (physical + inherited).sortedWith(compareBy({ it.area }, { it.inherited }))
  }

  private fun findAreaSource(
    slug: String,
    area: String,
    packs: Map<String, PlatformManifest>,
    visiting: Set<String> = emptySet(),
  ): Path? {
    if (slug in visiting) return null
    val pack = packs[slug] ?: return null
    pack.declaredFiles.areas[area]?.let { return it }
    return pack.codeReviewComposition?.baselineLayers.orEmpty().filter { it.required }.firstNotNullOfOrNull { layer ->
      packs[layer.platform]?.takeIf {
        it.routedSkillName == layer.skill
      }?.let { findAreaSource(layer.platform, area, packs, visiting + slug) }
    }
  }

  private fun metric(root: Path, pack: String, area: String, path: Path, inherited: Boolean): SpecialistMetric {
    val bullets = governedRuleBullets(Files.readString(path))
    val substantive = bullets.filter { isSubstantive(it, pack) }
    return SpecialistMetric(
      pack,
      area,
      path.relativeTo(root).toString(),
      inherited,
      substantive.size,
      CLUSTERS.count { cluster -> substantive.any(cluster::containsMatchIn) },
      substantive.count { hasEvidence(it, pack) },
      placeholders(Files.readString(path)),
    )
  }

  private fun governedRuleBullets(text: String): List<String> {
    var governed = false
    return text.lineSequence().mapNotNull { line ->
      when {
        line.startsWith("### ") -> {
          governed = RULE_HEADING.containsMatchIn(line)
          null
        }
        line.startsWith("## ") -> {
          governed = false
          null
        }
        governed && line.matches(Regex("^\\s*[-*]\\s+.+")) -> line.replaceFirst(Regex("^\\s*[-*]\\s+"), "")
        else -> null
      }
    }.toList()
  }

  private fun isSubstantive(rule: String, pack: String): Boolean = OBLIGATION.containsMatchIn(
    rule,
  ) && FAILURE.containsMatchIn(rule) && hasEvidence(rule, pack)
  private fun hasEvidence(rule: String, pack: String): Boolean {
    val candidates = EVIDENCE.findAll(rule).map { match ->
      match.groups[1]?.value ?: match.value.trim()
    }
    return candidates.any { candidate ->
      val normalized = candidate.lowercase(Locale.ROOT).trim('`', ' ', '.', '/', ':', '-', '_')
      normalized.isNotBlank() &&
        normalized !in setOf(pack.lowercase(Locale.ROOT), "platform", "api", "command", "mechanism", "example") &&
        !GENERIC_EVIDENCE.containsMatchIn(normalized)
    }
  }
  private fun placeholders(text: String): List<String> =
    PLACEHOLDERS.findAll(text).map { it.value.lowercase(Locale.ROOT) }.distinct().sorted().toList()

  private fun qualityFacets(text: String): List<String> = QUALITY_FACETS.filterValues { patterns ->
    patterns.all {
      it.containsMatchIn(text)
    }
  }.keys.sorted()

  private fun qualitySections(text: String): List<String> = REQUIRED_QUALITY_SECTIONS.filter { section ->
    text.lineSequence().any { it.trim() == "## $section" }
  }

  private fun resolveQualityCheck(slug: String, packs: Map<String, PlatformManifest>): Path? =
    packs[slug]?.declaredQualityCheckFile

  private fun correspondingPairs(files: List<AuthoredFile>): List<SimilarityPair> = files.groupBy {
    roleKey(it)
  }.values.flatMap { group ->
    group.sortedBy { it.path.toString() }.let { sorted ->
      sorted.indices.flatMap { left ->
        (left + 1 until sorted.size).map { right ->
          val first = sorted[left]
          val second = sorted[right]
          SimilarityPair(
            roleKey(first),
            relative(first.path),
            relative(second.path),
            jaccard(first.shingles, second.shingles),
          )
        }
      }
    }
  }.sortedWith(compareBy({ it.role }, { it.firstFile }, { it.secondFile }))

  private fun roleKey(file: AuthoredFile): String = when (file.role) {
    AuthoredFileRole.SPECIALIST -> "specialist:${file.area}"
    AuthoredFileRole.SIDECAR -> "sidecar:${file.area}:${file.path.name}"
    else -> file.role.name.lowercase(Locale.ROOT)
  }

  private fun relative(path: Path): String {
    val marker = "platform-packs/"
    val value = path.toString().replace('\\', '/')
    return value.substring(value.indexOf(marker).coerceAtLeast(0))
  }

  private fun jaccard(first: Set<String>, second: Set<String>): Fraction = fraction(
    first.intersect(second).size,
    first.union(second).size,
  )
  private fun fraction(numerator: Int, denominator: Int): Fraction =
    if (denominator == 0) Fraction(0, 1) else Fraction(numerator, denominator)
  private fun violation(metric: SpecialistMetric, metricName: String, measured: String, target: String, rule: String) =
    packViolation(metric.pack, "${metric.area}:$metricName", listOf(metric.file), measured, target, rule)
  private fun packViolation(
    pack: String,
    role: String,
    files: List<String>,
    measured: String,
    target: String,
    rule: String,
  ): SubstanceViolation {
    val id = (listOf(pack, role) + files).joinToString("|")
    return SubstanceViolation(id, pack, role, files.sorted(), measured, target, rule)
  }

  private fun readBaseline(root: Path): Pair<List<BaselineAcknowledgement>, List<String>> {
    val path = root.resolve("orchestration/review-orchestrator/platform-pack-substance-baseline.yaml")
    if (!path.isRegularFile()) {
      return emptyList<BaselineAcknowledgement>() to listOf(
        "platform pack substance baseline is missing",
      )
    }
    val raw = runCatching {
      Yaml().load<Any?>(Files.readString(path)) as? Map<*, *>
    }.getOrNull() ?: return emptyList<BaselineAcknowledgement>() to listOf(
      "platform pack substance baseline is invalid YAML",
    )
    if (raw["contract_version"]?.toString() != PLATFORM_PACK_SUBSTANCE_CONTRACT_VERSION) {
      return emptyList<BaselineAcknowledgement>() to listOf(
        "platform pack substance baseline contract_version must be $PLATFORM_PACK_SUBSTANCE_CONTRACT_VERSION",
      )
    }
    val entries = raw["acknowledgements"] as? List<*>
      ?: return emptyList<BaselineAcknowledgement>() to listOf(
        "platform pack substance baseline acknowledgements must be a list",
      )
    val errors = mutableListOf<String>()
    val parsed = entries.mapNotNull { value ->
      val entry = value as? Map<*, *> ?: return@mapNotNull null.also {
        errors += "baseline acknowledgement must be an object"
      }
      val owner = entry["owner"]?.toString().orEmpty()
      if (!Regex(
          "SKILL-114-[2-9]",
        ).matches(owner)
      ) {
        errors += "baseline owner '$owner' must be SKILL-114-2 through SKILL-114-9"
      }
      BaselineAcknowledgement(
        entry["id"]?.toString().orEmpty(),
        entry["measured"]?.toString().orEmpty(),
        entry["target"]?.toString().orEmpty(),
        owner,
      )
    }
    return parsed to errors
  }

  private fun rolePlaceholder(name: String): String = when {
    name.contains("code-check") -> "role-quality-check"
    name.contains("code-review-") -> "role-specialist"
    name.contains("code-review") -> "role-baseline"
    else -> "role-platform"
  }

  private val RULE_HEADING = Regex("rule|check|requirement|failure|correctness", RegexOption.IGNORE_CASE)
  private val OBLIGATION =
    Regex("\\b(must|never|require|reject|ensure|verify|flag|prevent|do not|fail)\\b", RegexOption.IGNORE_CASE)
  private val FAILURE =
    Regex(
      "\\b(fail|failure|reject|break|bug|risk|leak|loss|corrupt|deadlock|race|crash|invalid|incorrect|unsafe|consequence|regression|exposure|starvation|timeout)\\w*",
      RegexOption.IGNORE_CASE,
    )
  private val CLUSTERS = listOf(
    Regex("state|lifecycle|concurr|order|race|deadlock", RegexOption.IGNORE_CASE),
    Regex("contract|data|valid|auth|security|serial|exposure", RegexOption.IGNORE_CASE),
    Regex(
      "resource|performance|toolchain|build|operat|memory|latency|timeout|gradle|compiler",
      RegexOption.IGNORE_CASE,
    ),
  )
  private val PLACEHOLDERS =
    Regex(
      "\\b(TODO|FIXME|TBD|XXX)\\b|\\b(generic|example)\\s+(mechanism|api|command)\\b|\\b(fill|replace)\\s+(this|me|content)\\b",
      RegexOption.IGNORE_CASE,
    )
  private val QUALITY_FACETS = mapOf(
    "command-discovery" to listOf(Regex("discover|repository|wrapper|ci", RegexOption.IGNORE_CASE)),
    "concrete-tooling" to listOf(
      Regex("`[^`]*(?:check|test|lint|build|gradle|npm|cargo|go|swift)[^`]*`", RegexOption.IGNORE_CASE),
    ),
    "scoped-execution" to listOf(Regex("scope|targeted|changed files", RegexOption.IGNORE_CASE)),
    "failure-ownership" to listOf(Regex("belong|ownership|owned|scoped work", RegexOption.IGNORE_CASE)),
    "priority-fixes" to listOf(Regex("priority|ordered|fix ladder", RegexOption.IGNORE_CASE)),
    "rerun-escalation" to listOf(
      Regex("re-run|rerun", RegexOption.IGNORE_CASE),
      Regex("full suite|escalat", RegexOption.IGNORE_CASE),
    ),
    "blockers" to listOf(Regex("blocker|maintainer decision", RegexOption.IGNORE_CASE)),
  )
  private val REQUIRED_QUALITY_SECTIONS = listOf("Purpose", "Execution Steps", "Fix Strategy")
  private val EVIDENCE = Regex(
    "`([^`]+)`|(?:^|\\s)(?:./)?[a-z0-9_.-]+(?:gradle|lint|test|build|check|config)[a-z0-9_.:/-]*",
    RegexOption.IGNORE_CASE,
  )
  private val GENERIC_EVIDENCE = Regex(
    "(?:^|[\\s._-])(generic|example|placeholder)|(?:mechanism|api|command)(?:s)?$",
    RegexOption.IGNORE_CASE,
  )
  private val GENERATED_POINTER_NAMES =
    setOf(
      "review-orchestrator.md",
      "specialist-contract.md",
      "review-delegation.md",
      "review-scope.md",
      "shell-ceremony.md",
      "telemetry-contract.md",
      "stack-routing.md",
    )
}

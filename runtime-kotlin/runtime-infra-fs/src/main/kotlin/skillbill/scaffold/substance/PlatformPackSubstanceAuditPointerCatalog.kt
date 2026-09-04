package skillbill.scaffold.substance

import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.platformpack.CODE_REVIEW_FALLBACK_CAPABILITY
import skillbill.scaffold.policy.scaffold.APPROVED_CODE_REVIEW_AREAS
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.relativeTo

internal data class PackAuditContext(
  val root: Path,
  val policy: SubstancePolicy,
  val manifestsBySlug: Map<String, PlatformManifest>,
  val effectiveAreas: Map<String, Set<String>>,
  val files: List<AuthoredFile>,
  val pairs: List<SimilarityPair>,
)

internal data class PackQualityState(
  val path: Path?,
  val sections: List<String>,
  val facets: List<String>,
)

internal fun auditPlatformPacksImpl(repoRoot: Path, policy: SubstancePolicy): PlatformPackSubstanceReport {
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
  val context = PackAuditContext(root, policy, manifestsBySlug, effectiveAreas, files, pairs)
  val rawViolations = mutableListOf<SubstanceViolation>()
  val metrics = manifests.map { pack -> auditSinglePack(pack, context, rawViolations) }
  return PlatformPackSubstanceReport(
    PLATFORM_PACK_SUBSTANCE_CONTRACT_VERSION,
    metrics.sortedBy { it.pack },
    pairs,
    rawViolations.sortedBy { it.id },
    auditErrors,
  )
}

internal fun auditSinglePack(
  pack: PlatformManifest,
  context: PackAuditContext,
  rawViolations: MutableList<SubstanceViolation>,
): PackMetric {
  rawViolations += compositionViolations(pack, context.manifestsBySlug)
  val packFiles = context.files.filter { it.pack == pack.slug }
  val specialists = specialistMetrics(context.root, pack, context.effectiveAreas, context.manifestsBySlug)
  auditPackAreaCoverageViolations(pack, context, rawViolations)
  auditPackSpecialistViolations(specialists, context.policy, rawViolations)
  val quality = auditPackQualityState(pack, context)
  auditPackQualityViolations(pack, context, quality, rawViolations)
  val shared = auditPackSharedShingleViolation(pack, packFiles, context, rawViolations)
  val packPairs = context.pairs.filter { pair ->
    pair.firstFile.startsWith("platform-packs/${pack.slug}/") ||
      pair.secondFile.startsWith("platform-packs/${pack.slug}/")
  }
  auditPackPairViolations(pack, packPairs, context.policy, rawViolations)
  return PackMetric(
    pack.slug,
    pack.declaredCodeReviewAreas.sorted(),
    (context.effectiveAreas.getValue(pack.slug) - pack.declaredCodeReviewAreas.toSet()).sorted(),
    specialists,
    quality.path?.relativeTo(context.root)?.toString(),
    quality.sections,
    quality.facets,
    shared,
    packPairs.maxByOrNull { it.similarity },
  )
}

internal fun auditPackAreaCoverageViolations(
  pack: PlatformManifest,
  context: PackAuditContext,
  rawViolations: MutableList<SubstanceViolation>,
) {
  val missingAreas = APPROVED_CODE_REVIEW_AREAS - context.effectiveAreas.getValue(pack.slug)
  if (missingAreas.isEmpty()) return
  rawViolations += packViolation(
    PackViolationArgs(
      pack = pack.slug,
      role = "effective-area-coverage",
      files = listOf("platform-packs/${pack.slug}/platform.yaml"),
      measured = missingAreas.sorted().joinToString(","),
      target = "all-approved-areas",
      rule = "maintained pack must effectively cover every approved review area",
    ),
  )
}

internal fun auditPackSpecialistViolations(
  specialists: List<SpecialistMetric>,
  policy: SubstancePolicy,
  rawViolations: MutableList<SubstanceViolation>,
) {
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
}

internal fun auditPackQualityState(pack: PlatformManifest, context: PackAuditContext): PackQualityState {
  val qualityPath = resolveQualityCheck(pack.slug, context.manifestsBySlug)
  val qualityText = qualityPath?.let(Files::readString)
  return PackQualityState(
    path = qualityPath,
    sections = qualityText?.let(::qualitySections).orEmpty(),
    facets = qualityText?.let(::qualityFacets).orEmpty(),
  )
}

internal fun auditPackQualityViolations(
  pack: PlatformManifest,
  context: PackAuditContext,
  quality: PackQualityState,
  rawViolations: MutableList<SubstanceViolation>,
) {
  if (quality.path == null) {
    if (CODE_REVIEW_FALLBACK_CAPABILITY !in pack.fallbackCapabilities) {
      rawViolations += packViolation(
        PackViolationArgs(
          pack = pack.slug,
          role = "quality-check",
          files = emptyList(),
          measured = "absent",
          target = "present",
          rule = "maintained pack must declare a quality checker",
        ),
      )
    }
    return
  }
  if (quality.sections.size < REQUIRED_QUALITY_SECTIONS.size) {
    rawViolations += packViolation(
      PackViolationArgs(
        pack = pack.slug,
        role = "quality-check-sections",
        files = listOf(quality.path.relativeTo(context.root).toString()),
        measured = quality.sections.joinToString(",").ifEmpty { "none" },
        target = REQUIRED_QUALITY_SECTIONS.joinToString(","),
        rule = "quality checker must contain every governed section",
      ),
    )
  }
  if (quality.facets.size < context.policy.minimumQualityFacets) {
    rawViolations += packViolation(
      PackViolationArgs(
        pack = pack.slug,
        role = "quality-check",
        files = listOf(quality.path.relativeTo(context.root).toString()),
        measured = quality.facets.size.toString(),
        target = "${context.policy.minimumQualityFacets}",
        rule = "quality checker must cover every depth facet",
      ),
    )
  }
}

internal fun auditPackSharedShingleViolation(
  pack: PlatformManifest,
  packFiles: List<AuthoredFile>,
  context: PackAuditContext,
  rawViolations: MutableList<SubstanceViolation>,
): Fraction {
  val packShingles = packFiles.flatMap { it.shingles }.toSet()
  val otherShingles = context.files.filter { it.pack != pack.slug }.flatMap { it.shingles }.toSet()
  val shared = fraction(packShingles.count { it in otherShingles }, packShingles.size)
  if (shared > context.policy.maximumSharedShingles) {
    rawViolations += packViolation(
      PackViolationArgs(
        pack = pack.slug,
        role = "shared-shingles",
        files = packFiles.map { it.path.relativeTo(context.root).toString() },
        measured = shared.percentage(),
        target = "<=35.00%",
        rule = "pack exceeds shared normalized five-word sequence threshold",
      ),
    )
  }
  return shared
}

internal fun auditPackPairViolations(
  pack: PlatformManifest,
  packPairs: List<SimilarityPair>,
  policy: SubstancePolicy,
  rawViolations: MutableList<SubstanceViolation>,
) {
  packPairs.filter { it.similarity > policy.maximumPairSimilarity }.forEach { pair ->
    rawViolations += packViolation(
      PackViolationArgs(
        pack = pack.slug,
        role = "pair:${pair.role}",
        files = listOf(pair.firstFile, pair.secondFile),
        measured = pair.similarity.percentage(),
        target = "<=65.00%",
        rule = "corresponding authored rubrics exceed similarity threshold",
      ),
    )
  }
}

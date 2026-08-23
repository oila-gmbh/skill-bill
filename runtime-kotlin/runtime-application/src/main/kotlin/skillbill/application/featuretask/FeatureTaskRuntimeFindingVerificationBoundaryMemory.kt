package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemoryRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemorySection
import skillbill.contracts.JsonSupport
import skillbill.error.GoalVerificationBoundaryCapExceededError
import skillbill.ports.goalrunner.GoalPlanningBoundaryBodyResolver
import skillbill.ports.goalrunner.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.model.GoalPlanningBoundaryBodyResolutionCaps
import skillbill.ports.goalrunner.model.GoalPlanningBoundaryHeading
import skillbill.ports.goalrunner.model.GoalPlanningResolvedBoundaryBodies
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import java.nio.file.Path

@Inject
@Suppress("TooManyFunctions", "ReturnCount", "LoopWithTooManyJumpStatements")
class FeatureTaskRuntimeFindingVerificationBoundaryMemory(
  private val contextDiscovery: GoalPlanningContextDiscovery,
  private val boundaryBodyResolver: GoalPlanningBoundaryBodyResolver,
) {
  fun sectionsForFindings(
    repoRoot: Path,
    requests: List<FeatureTaskRuntimeFindingBoundaryMemoryRequest>,
  ): List<FeatureTaskRuntimeFindingBoundaryMemorySection> = requests.map { request ->
    FeatureTaskRuntimeFindingBoundaryMemorySection(
      findingId = request.findingId,
      discovery = contextDiscovery.discoverForFindingPaths(
        repoRoot = repoRoot,
        findingPaths = request.findingPaths,
        loudFailOnCapExceeded = false,
      ),
    )
  }

  fun boundarySelectionsForResolvedBodies(
    persisted: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>?,
  ): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>? = persisted?.takeIf { it.isNotEmpty() }

  fun resolveSelectedBodies(
    repoRoot: Path,
    catalog: List<GoalPlanningBoundaryHeading>,
    selectedHeadingIds: List<String>,
  ): GoalPlanningResolvedBoundaryBodies = boundaryBodyResolver.resolve(
    repoRoot = repoRoot,
    headingIds = selectedHeadingIds,
    catalogHeadingIds = catalog.map(GoalPlanningBoundaryHeading::headingId).toSet(),
    caps = GoalPlanningBoundaryBodyResolutionCaps.VERIFICATION,
    loudFailOnCapExceeded = true,
  )

  fun selectionsRequiringBodyDelivery(
    sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>> {
    val sectionByFindingId = sections.associateBy(FeatureTaskRuntimeFindingBoundaryMemorySection::findingId)
    return dispositions.mapNotNull { disposition ->
      val section = sectionByFindingId[disposition.findingId] ?: return@mapNotNull null
      if (section.discovery.boundaryContextUnavailable || disposition.selectedBoundaryHeadings.isEmpty()) {
        return@mapNotNull null
      }
      disposition.findingId to disposition.selectedBoundaryHeadings
    }.toMap()
  }

  fun validateDispositionBoundaryContext(
    sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): String? {
    val sectionByFindingId = sections.associateBy(FeatureTaskRuntimeFindingBoundaryMemorySection::findingId)
    for (disposition in dispositions) {
      val section = sectionByFindingId[disposition.findingId] ?: continue
      if (section.discovery.boundaryContextUnavailable) {
        if (!disposition.boundaryContextUnavailable) {
          return "finding verification disposition for ${disposition.findingId} must set " +
            "boundary_context_unavailable when no eligible boundary owns its paths."
        }
        if (disposition.selectedBoundaryHeadings.isNotEmpty()) {
          return "finding verification disposition for ${disposition.findingId} must not select " +
            "boundary headings when boundary context is unavailable."
        }
        continue
      }
      if (disposition.boundaryContextUnavailable) {
        return "finding verification disposition for ${disposition.findingId} must not set " +
          "boundary_context_unavailable when a scoped boundary catalog is available."
      }
    }
    return null
  }

  fun validateDispositionBoundaryProvenance(
    sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): String? {
    val sectionByFindingId = sections.associateBy(FeatureTaskRuntimeFindingBoundaryMemorySection::findingId)
    for (disposition in dispositions) {
      if (disposition.boundaryContextUnavailable || disposition.selectedBoundaryHeadings.isEmpty()) continue
      val catalog = sectionByFindingId[disposition.findingId]?.discovery?.boundaryCatalog.orEmpty()
        .associateBy(GoalPlanningBoundaryHeading::headingId)
      for (heading in disposition.selectedBoundaryHeadings) {
        val catalogEntry = catalog[heading.headingId]
          ?: return "finding verification disposition for ${disposition.findingId} selects heading_id " +
            "'${heading.headingId}' absent from the scoped boundary catalog."
        if (catalogEntry.sourcePath != heading.sourcePath) {
          return "finding verification disposition for ${disposition.findingId} records source_path " +
            "'${heading.sourcePath}' for heading_id '${heading.headingId}' but the catalog declares " +
            "'${catalogEntry.sourcePath}'."
        }
      }
    }
    return null
  }

  fun validatePersistedBoundarySelectionsMatch(
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
    persisted: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): String? {
    for ((findingId, persistedForFinding) in persisted) {
      val disposition = dispositions.firstOrNull { it.findingId == findingId }
        ?: return "finding verification disposition must cover every finding whose boundary headings were " +
          "delivered; missing finding_id $findingId."
      if (!boundaryHeadingsMatch(persistedForFinding, disposition.selectedBoundaryHeadings)) {
        return "finding verification disposition for $findingId must reuse the persisted " +
          "selected_boundary_headings verbatim; do not change heading selections after the initial settlement pass."
      }
    }
    return null
  }

  fun validatePersistedBoundarySelectionsAgainstCatalog(
    sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
    persisted: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): String? {
    val sectionByFindingId = sections.associateBy(FeatureTaskRuntimeFindingBoundaryMemorySection::findingId)
    for ((findingId, selections) in persisted) {
      if (selections.isEmpty()) continue
      val catalog = sectionByFindingId[findingId]?.discovery?.boundaryCatalog.orEmpty()
        .associateBy(GoalPlanningBoundaryHeading::headingId)
      for (heading in selections) {
        val catalogEntry = catalog[heading.headingId]
          ?: return "persisted boundary selection for $findingId references heading_id " +
            "'${heading.headingId}' absent from the scoped boundary catalog."
        if (catalogEntry.sourcePath != heading.sourcePath) {
          return "persisted boundary selection for $findingId records source_path " +
            "'${heading.sourcePath}' for heading_id '${heading.headingId}' but the catalog declares " +
            "'${catalogEntry.sourcePath}'."
        }
      }
    }
    return null
  }

  fun validateBoundarySelectionsDelivered(
    sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
    persisted: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>?,
  ): String? {
    val pending = selectionsRequiringBodyDelivery(sections, dispositions)
    if (persisted != null) {
      validatePersistedBoundarySelectionsMatch(dispositions, persisted)?.let { return it }
      validatePersistedBoundarySelectionsAgainstCatalog(sections, persisted)?.let { return it }
      for ((findingId, pendingSelections) in pending) {
        val persistedForFinding = persisted[findingId]
          ?: return "finding verification disposition for $findingId selected boundary headings but " +
            "resolved entry bodies were not yet delivered; re-read the briefing after the runtime records selections."
        if (!boundaryHeadingsMatch(persistedForFinding, pendingSelections)) {
          return "finding verification disposition for $findingId must reuse the persisted " +
            "selected_boundary_headings verbatim; do not change heading selections after the initial settlement pass."
        }
      }
      return null
    }
    if (pending.isNotEmpty()) {
      return "finding verification selected boundary headings but resolved entry bodies were not yet " +
        "delivered; re-read the briefing after the runtime records selections."
    }
    return null
  }

  fun validateDispositionBoundaryBodies(
    repoRoot: Path,
    sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
    persistedSelections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>? = null,
  ): String? {
    val sectionByFindingId = sections.associateBy(FeatureTaskRuntimeFindingBoundaryMemorySection::findingId)
    for (disposition in dispositions) {
      val selections = persistedSelections?.get(disposition.findingId) ?: disposition.selectedBoundaryHeadings
      if (selections.isEmpty()) continue
      val section = sectionByFindingId[disposition.findingId] ?: continue
      if (section.discovery.boundaryContextUnavailable) continue
      try {
        resolveSelectedBodies(
          repoRoot = repoRoot,
          catalog = section.discovery.boundaryCatalog,
          selectedHeadingIds = selections.map(FeatureTaskRuntimeVerificationBoundaryHeadingProvenance::headingId),
        )
      } catch (error: GoalVerificationBoundaryCapExceededError) {
        return error.message ?: "finding verification boundary body resolution exceeded a verification cap."
      }
    }
    return null
  }

  fun resolvedBodiesPromptSection(
    repoRoot: Path,
    sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
    selectionsByFindingId: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): String {
    if (selectionsByFindingId.isEmpty()) return ""
    validatePersistedBoundarySelectionsAgainstCatalog(sections, selectionsByFindingId)?.let { reason ->
      error(reason)
    }
    val sectionByFindingId = sections.associateBy(FeatureTaskRuntimeFindingBoundaryMemorySection::findingId)
    return buildString {
      appendLine()
      appendLine("## Selected boundary memory (verify_findings)")
      appendLine(
        "Resolved entry bodies for the heading ids you selected. Unselected bodies and whole " +
          "history.md or decisions.md files are absent by design.",
      )
      for ((findingId, selections) in selectionsByFindingId) {
        val section = sectionByFindingId[findingId] ?: continue
        val resolved = resolveSelectedBodies(
          repoRoot = repoRoot,
          catalog = section.discovery.boundaryCatalog,
          selectedHeadingIds = selections.map(FeatureTaskRuntimeVerificationBoundaryHeadingProvenance::headingId),
        )
        appendLine()
        appendLine("### Finding $findingId")
        for (body in resolved.bodies) {
          appendLine("#### ${body.headingId}")
          appendLine(body.heading)
          appendLine(body.body)
        }
        if (resolved.unresolvedHeadingIds.isNotEmpty()) {
          appendLine(
            "Unresolved selections (no body delivered): ${resolved.unresolvedHeadingIds.joinToString(", ")}",
          )
        }
      }
    }
  }

  fun promptSection(sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>): String {
    if (sections.isEmpty()) return ""
    return buildString {
      appendLine()
      appendLine("## Scoped boundary memory (verify_findings)")
      appendLine(
        "Each finding below carries a titles-only heading catalog scoped to the boundaries that own " +
          "its paths. Select heading_id values semantically in each disposition's " +
          "selected_boundary_headings. Only selected entry bodies are resolved after settlement; " +
          "unselected bodies and whole history.md or decisions.md files never belong in this briefing.",
      )
      for (section in sections) {
        appendLine()
        appendLine("### Finding ${section.findingId}")
        if (section.discovery.boundaryContextUnavailable) {
          appendLine("boundary_context_unavailable: true")
          appendLine("Proceed intent-only; no eligible boundary owns this finding's paths.")
          continue
        }
        appendLine("boundary_catalog:")
        appendLine(
          JsonSupport.mapToJsonString(
            mapOf(
              "headings" to section.discovery.boundaryCatalog.map { heading ->
                mapOf(
                  "heading_id" to heading.headingId,
                  "source_path" to heading.sourcePath,
                  "kind" to heading.kind,
                  "heading" to heading.heading,
                )
              },
              "truncated" to section.discovery.boundaryCatalogTruncated,
            ),
          ),
        )
      }
    }
  }
}

private fun boundaryHeadingsMatch(
  persisted: List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>,
  disposition: List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>,
): Boolean {
  if (persisted.size != disposition.size) return false
  return persisted.zip(disposition).all { (left, right) ->
    left.headingId == right.headingId && left.sourcePath == right.sourcePath
  }
}

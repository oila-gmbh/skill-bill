package skillbill.desktop.core.domain.model

/**
 * Scaffold kind identifiers. Retired partial kinds remain modeled so legacy source records can be
 * displayed and removed, but only [activeCreationValues] are offered by creation surfaces.
 */
enum class ScaffoldKind(
  val payloadKind: String,
  val displayLabel: String,
  val creationSupported: Boolean,
) {
  HORIZONTAL_SKILL("horizontal", "Horizontal skill", true),
  PLATFORM_PACK("platform-pack", "Platform pack", true),
  PLATFORM_OVERRIDE_PILOTED("platform-override-piloted", "Platform override for piloted family", false),
  CODE_REVIEW_AREA("code-review-area", "Code-review area", false),
  ADD_ON("add-on", "Add-on", true);

  companion object {
    fun activeCreationValues(): List<ScaffoldKind> = entries.filter { it.creationSupported }
  }
}

/**
 * Skeleton-mode selector for platform-pack scaffolds. Mirrors the runtime payload field.
 */
enum class ScaffoldPlatformPackSkeleton {
  STARTER,
  FULL,
}

/**
 * Sealed payload, one variant per supported wizard kind. Every variant exposes a single
 * [toContractMap] entry point that produces a SCAFFOLD_PAYLOAD.md v1.0-compliant map (the same
 * shape the JSON contract describes). Wizards never hand-construct these maps directly; they only
 * fill in the strongly-typed variant fields.
 */
sealed class ScaffoldPayload {
  abstract val kind: ScaffoldKind

  /** Repo root override, or null to let the runtime use `System.getProperty("user.dir")`. */
  abstract val repoRoot: String?

  /**
   * Project this payload onto a contract-valid `Map<String, Any?>` matching SCAFFOLD_PAYLOAD.md
   * v1.0. The shape is identical for dry-run and execute calls — execution mode is carried by
   * the gateway, not by the payload itself (AC2).
   */
  fun toContractMap(): Map<String, Any?> {
    val base = linkedMapOf<String, Any?>(
      "scaffold_payload_version" to SCAFFOLD_PAYLOAD_VERSION,
      "kind" to kind.payloadKind,
    )
    repoRoot?.let { base["repo_root"] = it }
    fillContractFields(base)
    return base
  }

  protected abstract fun fillContractFields(target: MutableMap<String, Any?>)

  companion object {
    const val SCAFFOLD_PAYLOAD_VERSION: String = "1.0"
  }

  data class HorizontalSkill(
    val name: String,
    val description: String = "",
    val contentBody: String? = null,
    val subagentSpecialists: List<String> = emptyList(),
    val suppressSubagents: Boolean = false,
    override val repoRoot: String? = null,
  ) : ScaffoldPayload() {
    override val kind: ScaffoldKind = ScaffoldKind.HORIZONTAL_SKILL

    override fun fillContractFields(target: MutableMap<String, Any?>) {
      target["name"] = name
      if (description.isNotBlank()) target["description"] = description
      contentBody?.let { target["content_body"] = it }
      if (subagentSpecialists.isNotEmpty()) target["subagent_specialists"] = subagentSpecialists
      if (suppressSubagents) target["no_subagents"] = true
    }
  }

  data class PlatformPack(
    val platform: String,
    val displayName: String = "",
    val description: String = "",
    val skeletonMode: ScaffoldPlatformPackSkeleton = ScaffoldPlatformPackSkeleton.FULL,
    /**
     * Optional specialist-area override (must come from [ScaffoldCatalogSnapshot.approvedCodeReviewAreas]).
     * When non-empty, the wizard must not also send a skeleton mode — the contract is mutually exclusive.
     */
    val specialistAreas: List<String> = emptyList(),
    val strongRoutingSignals: List<String> = emptyList(),
    val tieBreakers: List<String> = emptyList(),
    val baselineLayers: List<ScaffoldBaselineLayerPayload> = emptyList(),
    val subagentSpecialists: List<String> = emptyList(),
    val suppressSubagents: Boolean = false,
    val contentBody: String? = null,
    override val repoRoot: String? = null,
  ) : ScaffoldPayload() {
    override val kind: ScaffoldKind = ScaffoldKind.PLATFORM_PACK

    override fun fillContractFields(target: MutableMap<String, Any?>) {
      target["platform"] = platform
      if (displayName.isNotBlank()) target["display_name"] = displayName
      if (description.isNotBlank()) target["description"] = description
      if (specialistAreas.isNotEmpty()) {
        target["specialist_areas"] = specialistAreas
      } else {
        target["skeleton_mode"] = when (skeletonMode) {
          ScaffoldPlatformPackSkeleton.STARTER -> "starter"
          ScaffoldPlatformPackSkeleton.FULL -> "full"
        }
      }
      if (strongRoutingSignals.isNotEmpty() || tieBreakers.isNotEmpty()) {
        val routing = linkedMapOf<String, Any?>()
        if (strongRoutingSignals.isNotEmpty()) routing["strong"] = strongRoutingSignals
        if (tieBreakers.isNotEmpty()) routing["tie_breakers"] = tieBreakers
        target["routing_signals"] = routing
      }
      if (baselineLayers.isNotEmpty()) {
        target["baseline_layers"] = baselineLayers.map { layer ->
          linkedMapOf<String, Any?>(
            "platform" to layer.platform,
            "skill" to layer.skill,
            "scope" to layer.scope,
            "required" to layer.required,
            "mode" to layer.mode,
          )
        }
      }
      if (subagentSpecialists.isNotEmpty()) target["subagent_specialists"] = subagentSpecialists
      if (suppressSubagents) target["no_subagents"] = true
      contentBody?.let { target["content_body"] = it }
    }
  }

  data class PlatformOverride(
    val platform: String,
    val family: String,
    val description: String = "",
    val contentBody: String? = null,
    val subagentSpecialists: List<String> = emptyList(),
    val suppressSubagents: Boolean = false,
    override val repoRoot: String? = null,
  ) : ScaffoldPayload() {
    override val kind: ScaffoldKind = ScaffoldKind.PLATFORM_OVERRIDE_PILOTED

    override fun fillContractFields(target: MutableMap<String, Any?>) {
      target["platform"] = platform
      target["family"] = family
      if (description.isNotBlank()) target["description"] = description
      contentBody?.let { target["content_body"] = it }
      if (subagentSpecialists.isNotEmpty()) target["subagent_specialists"] = subagentSpecialists
      if (suppressSubagents) target["no_subagents"] = true
    }
  }

  data class CodeReviewArea(
    val platform: String,
    val area: String,
    val description: String = "",
    val contentBody: String? = null,
    override val repoRoot: String? = null,
  ) : ScaffoldPayload() {
    override val kind: ScaffoldKind = ScaffoldKind.CODE_REVIEW_AREA

    override fun fillContractFields(target: MutableMap<String, Any?>) {
      target["platform"] = platform
      target["area"] = area
      if (description.isNotBlank()) target["description"] = description
      contentBody?.let { target["content_body"] = it }
    }
  }

  data class AddOn(
    val name: String,
    val platform: String,
    val description: String = "",
    val body: String? = null,
    override val repoRoot: String? = null,
  ) : ScaffoldPayload() {
    override val kind: ScaffoldKind = ScaffoldKind.ADD_ON

    override fun fillContractFields(target: MutableMap<String, Any?>) {
      target["name"] = name
      target["platform"] = platform
      if (description.isNotBlank()) target["description"] = description
      body?.let { target["body"] = it }
    }
  }
}

data class ScaffoldBaselineLayerPayload(
  val platform: String,
  val skill: String,
  val scope: String,
  val required: Boolean,
  val mode: String,
)

/**
 * Snapshot of [ScaffoldCatalog] entries exposed to the wizard UI. Common-main does not have
 * access to runtime-core, so the JVM gateway projects the snapshot at composition time.
 */
data class ScaffoldCatalogSnapshot(
  val approvedCodeReviewAreas: List<String>,
  val preShellFamilies: List<String>,
  val shelledFamilies: List<String>,
  val platformPackPresets: List<PlatformPackPresetEntry>,
  val pilotedPlatformPacks: List<PilotedPlatformPackEntry>,
  val baselineReviewPacks: List<BaselineReviewPackOption>,
  val baselineReviewCompositionEdges: List<BaselineReviewCompositionEdge>,
  val baselineReviewLayerSuggestions: List<BaselineReviewLayerSuggestion>,
  val scaffoldPayloadVersion: String,
) {
  companion object {
    val empty: ScaffoldCatalogSnapshot = ScaffoldCatalogSnapshot(
      approvedCodeReviewAreas = emptyList(),
      preShellFamilies = emptyList(),
      shelledFamilies = emptyList(),
      platformPackPresets = emptyList(),
      pilotedPlatformPacks = emptyList(),
      baselineReviewPacks = emptyList(),
      baselineReviewCompositionEdges = emptyList(),
      baselineReviewLayerSuggestions = emptyList(),
      scaffoldPayloadVersion = ScaffoldPayload.SCAFFOLD_PAYLOAD_VERSION,
    )
  }
}

data class PlatformPackPresetEntry(
  val platform: String,
  val displayName: String,
)

data class PilotedPlatformPackEntry(
  val platform: String,
  val displayName: String,
)

data class BaselineReviewPackOption(
  val platform: String,
  val displayName: String,
  val strongRoutingSignals: List<String>,
  val skills: List<BaselineReviewSkillOption>,
)

data class BaselineReviewSkillOption(
  val name: String,
  val supportedModes: List<String>,
  val supportedScopes: List<String>,
)

data class BaselineReviewCompositionEdge(
  val sourcePlatform: String,
  val targetPlatform: String,
  val targetSkill: String,
)

data class BaselineReviewLayerSuggestion(
  val label: String,
  val triggerSignals: List<String>,
  val platform: String,
  val skill: String,
  val scope: String,
  val required: Boolean,
  val mode: String,
)

/**
 * Mirror of the runtime's `ScaffoldResult` fields, lifted into a common-main-safe DTO so the
 * scaffold preview and success surfaces don't import `java.nio.file.Path` from JVM-only code.
 */
data class ScaffoldPlan(
  val kind: String,
  val skillName: String,
  val skillPath: String,
  val createdFiles: List<String> = emptyList(),
  val manifestEdits: List<String> = emptyList(),
  val manifestPreviews: List<ManifestEditPreview> = emptyList(),
  val symlinks: List<String> = emptyList(),
  val installTargets: List<String> = emptyList(),
  val notes: List<String> = emptyList(),
)

data class ManifestEditPreview(
  val path: String,
  val content: String,
)

/** Mirror DTO for the `ScaffoldResult` produced by execute mode. Same shape as [ScaffoldPlan]. */
data class ScaffoldOutcome(
  val kind: String,
  val skillName: String,
  val skillPath: String,
  val createdFiles: List<String> = emptyList(),
  val manifestEdits: List<String> = emptyList(),
  val symlinks: List<String> = emptyList(),
  val installTargets: List<String> = emptyList(),
  val notes: List<String> = emptyList(),
)

/**
 * Sealed result so the view model can react to scaffold outcomes without try/catch across the
 * coroutine boundary. The runtime is loud-fail; the gateway translates exceptions into [Failed].
 */
sealed class ScaffoldRunResult {
  /** Dry-run succeeded; the planned files/manifest edits are listed inside [planned]. */
  data class Preview(val planned: ScaffoldPlan) : ScaffoldRunResult()

  /** Execute mode succeeded transactionally; [result] reflects what landed on disk. */
  data class Success(val result: ScaffoldOutcome) : ScaffoldRunResult()

  /**
   * Either mode failed.
   *
   * - [exceptionName] is the runtime exception's simple class name (verbatim, AC5).
   * - [exceptionMessage] is the runtime exception's `message` field (empty when blank).
   * - [rollbackComplete] is `true` when the runtime guarantees the repo is in its pre-scaffold
   *   state. It is `false` only when the runtime raised `ScaffoldRollbackError`, which means
   *   the rollback itself failed and the repo may be partially mutated.
   */
  data class Failed(
    val exceptionName: String,
    val exceptionMessage: String,
    val rollbackComplete: Boolean,
  ) : ScaffoldRunResult()
}

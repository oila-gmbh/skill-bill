package skillbill.scaffold.runtime

import skillbill.error.InvalidScaffoldPayloadError
import skillbill.error.MissingPlatformPackError
import skillbill.error.SkillAlreadyExistsError
import skillbill.error.UnknownSkillKindError
import skillbill.scaffold.platformpack.loadPlatformPack
import skillbill.scaffold.policy.scaffold.SKILL_KIND_ADD_ON
import skillbill.scaffold.policy.scaffold.SKILL_KIND_AGENT_ADDON
import skillbill.scaffold.policy.scaffold.SKILL_KIND_CODE_REVIEW_AREA
import skillbill.scaffold.policy.scaffold.SKILL_KIND_HORIZONTAL
import skillbill.scaffold.policy.scaffold.SKILL_KIND_PLATFORM_OVERRIDE_PILOTED
import skillbill.scaffold.policy.scaffold.SKILL_KIND_PLATFORM_PACK
import skillbill.scaffold.policy.scaffold.sharedContractNote
import skillbill.scaffold.rendering.defaultAreaFocus
import java.nio.file.Files
import java.nio.file.Path
import skillbill.scaffold.payload.optionalSpecialistSubagents as policyOptionalSpecialistSubagents
import skillbill.scaffold.payload.rejectBaselineLayersForNonPlatformPack as policyRejectBaselineLayersForNonPlatformPack
import skillbill.scaffold.payload.rejectLeafSubagentSpecialists as policyRejectLeafSubagentSpecialists
import skillbill.scaffold.payload.requireStringMap as requireString
import skillbill.scaffold.payload.requireStringOrDefaultMap as requireStringOrDefault
import skillbill.scaffold.payload.resolvePlatformPackDefaults as policyResolvePlatformPackDefaults

internal fun executeScaffold(
  txn: ScaffoldTransaction,
  plan: ScaffoldPlan,
  repoRoot: Path,
  adapters: ScaffoldAdapterSeams,
): ScaffoldExecutionResult {
  val execution =
    when (plan.kind) {
      SKILL_KIND_PLATFORM_PACK -> createPlatformPack(txn, plan, repoRoot)
      else -> stageSingleScaffold(txn, plan, repoRoot)
    }
  adapters.validateScaffold(plan, repoRoot)
  val (installTargets, installNotes) = performInstall(txn, plan, repoRoot)
  return execution.copy(
    installTargets = installTargets,
    notes = execution.notes + installNotes + subagentEmissionNotes(plan),
  )
}

internal fun resolveRepoRoot(payload: Map<String, Any?>): Path {
  val repoRootRaw = payload["repo_root"] as? String ?: return defaultRepoRoot()
  if (repoRootRaw.isBlank()) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'repo_root' must be a non-empty string when provided.",
    )
  }
  return Path.of(repoRootRaw).toAbsolutePath().normalize()
}

internal fun planScaffold(
  payload: Map<String, Any?>,
  repoRoot: Path,
  kind: String,
  adapters: ScaffoldAdapterSeams,
): ScaffoldPlan = when (kind) {
  SKILL_KIND_HORIZONTAL -> {
    policyRejectBaselineLayersForNonPlatformPack(payload, kind)
    planHorizontal(payload, repoRoot)
  }
  SKILL_KIND_PLATFORM_OVERRIDE_PILOTED -> {
    policyRejectBaselineLayersForNonPlatformPack(payload, kind)
    planPlatformOverridePiloted(payload, repoRoot)
  }
  SKILL_KIND_PLATFORM_PACK -> planPlatformPack(payload, repoRoot, adapters)
  SKILL_KIND_CODE_REVIEW_AREA -> {
    policyRejectBaselineLayersForNonPlatformPack(payload, kind)
    planCodeReviewArea(payload, repoRoot)
  }
  SKILL_KIND_ADD_ON -> {
    policyRejectBaselineLayersForNonPlatformPack(payload, kind)
    planAddOn(payload, repoRoot, adapters)
  }
  SKILL_KIND_AGENT_ADDON -> {
    policyRejectBaselineLayersForNonPlatformPack(payload, kind)
    planAgentAddon(payload, repoRoot)
  }
  else -> throw UnknownSkillKindError("Scaffold payload declares unsupported kind '$kind'.")
}

internal fun planHorizontal(payload: Map<String, Any?>, repoRoot: Path): ScaffoldPlan {
  val name = requireString(payload, "name")
  val skillPath = repoRoot.resolve("skills").resolve(name)
  val subagents = policyOptionalSpecialistSubagents(payload, SKILL_KIND_HORIZONTAL)
  return ScaffoldPlan(
    kind = SKILL_KIND_HORIZONTAL,
    skillName = name,
    skillPath = skillPath,
    skillFile = skillPath.resolve("SKILL.md"),
    contentFile = skillPath.resolve("content.md"),
    family = "horizontal",
    platform = "",
    area = "",
    isShelled = false,
    notes = emptyList(),
    description = requireStringOrDefault(payload, "description", ""),
    contentBody = payload["content_body"] as? String,
    subagentSpecialists = subagents.specialists,
    subagentsSuppressed = subagents.suppressed,
  )
}

internal fun planPlatformOverridePiloted(payload: Map<String, Any?>, repoRoot: Path): ScaffoldPlan {
  val platform = requireString(payload, "platform")
  val family = requireString(payload, "family")
  val name = canonicalName(payload, defaultName = defaultPlatformOverrideName(platform, family))
  val subagents = policyOptionalSpecialistSubagents(payload, SKILL_KIND_PLATFORM_OVERRIDE_PILOTED)
  val isShelled = family in SHELLED_FAMILIES
  if (!isShelled) {
    return planPreShellPlatformOverride(
      ScaffoldPlatformOverridePlanArgs(payload, repoRoot, platform, family, name, subagents),
    )
  }
  return planShelledPlatformOverride(
    ScaffoldPlatformOverridePlanArgs(payload, repoRoot, platform, family, name, subagents),
  )
}

internal fun planPlatformPack(
  payload: Map<String, Any?>,
  repoRoot: Path,
  adapters: ScaffoldAdapterSeams,
): ScaffoldPlan {
  val platform = requireString(payload, "platform")
  rejectPlatformPackSubagentOverrides(payload)
  val defaults = policyResolvePlatformPackDefaults(payload, platform)
  val packRoot = repoRoot.resolve("platform-packs").resolve(platform)
  if (Files.exists(packRoot)) {
    throw SkillAlreadyExistsError(
      "Platform pack target '$packRoot' already exists. " +
        "Remove it or pick a new platform slug before retrying.",
    )
  }
  return buildPlatformPackScaffoldPlan(
    PlatformPackScaffoldPlanArgs(payload, repoRoot, adapters, platform, defaults, packRoot),
  )
}

internal fun rejectPlatformPackSubagentOverrides(payload: Map<String, Any?>) {
  val field = listOf("subagent_specialists", "no_subagents").firstOrNull(payload::containsKey) ?: return
  throw InvalidScaffoldPayloadError(
    "Scaffold payload field '$field' is not supported for kind 'platform-pack'; " +
      "the review structure standard requires exactly one manifest-derived native agent per declared specialist.",
  )
}

internal fun specialistFocus(displayName: String, area: String, routingSignals: List<String>): String =
  "$displayName ${defaultAreaFocus(area)} across ${routingSignals.joinToString(", ")} signals"

// SKILL-52.1 subtask 3 (AC1): `optionalBaselineLayers`, `validateBaselineLayerPayloadReferences`,
// and `validateBaselineLayerModeSupport` now live on `FileSystemScaffoldRepoValidation`.
// Callsites below delegate to `scaffoldRepoValidation.optionalBaselineLayers(...)`.

internal fun planCodeReviewArea(payload: Map<String, Any?>, repoRoot: Path): ScaffoldPlan {
  policyRejectLeafSubagentSpecialists(payload, SKILL_KIND_CODE_REVIEW_AREA)
  val platform = requireString(payload, "platform")
  val area = requireString(payload, "area")
  if (area !in APPROVED_CODE_REVIEW_AREAS) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload declares code-review area '$area' that is not in the approved set $APPROVED_CODE_REVIEW_AREAS.",
    )
  }
  val name = canonicalName(payload, defaultName = "bill-$platform-code-review-$area")
  val packRoot = repoRoot.resolve("platform-packs").resolve(platform)
  val manifestPath = packRoot.resolve("platform.yaml")
  if (!Files.isRegularFile(manifestPath)) {
    throw MissingPlatformPackError(
      "Platform pack '$platform' does not exist at '$packRoot'. " +
        "Create a conforming platform.yaml before adding a code-review area to it.",
    )
  }
  val pack = loadPlatformPack(packRoot)
  val skillPath = packRoot.resolve("code-review").resolve(name)
  return ScaffoldPlan(
    kind = SKILL_KIND_CODE_REVIEW_AREA,
    skillName = name,
    skillPath = skillPath,
    skillFile = skillPath.resolve("SKILL.md"),
    contentFile = skillPath.resolve("content.md"),
    family = "code-review",
    platform = platform,
    area = area,
    isShelled = true,
    notes = listOf(sharedContractNote()),
    displayName = pack.displayName ?: deriveDisplayName(platform),
    description = requireStringOrDefault(payload, "description", ""),
    contentBody = payload["content_body"] as? String,
  )
}

@file:Suppress("TooGenericExceptionCaught", "MaxLineLength")

package skillbill.scaffold.runtime

import skillbill.agentaddon.AgentAddonSchemaValidator
import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.error.InvalidScaffoldPayloadError
import skillbill.error.MissingPlatformPackError
import skillbill.error.ScaffoldRollbackError
import skillbill.error.SkillAlreadyExistsError
import skillbill.error.UnknownPreShellFamilyError
import skillbill.error.UnknownSkillKindError
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallTransaction
import skillbill.install.plan.InstallContext
import skillbill.install.plan.detectAgents
import skillbill.install.plan.installSkill
import skillbill.install.plan.uninstallTargets
import skillbill.scaffold.authoring.parseInternalForFrontmatter
import skillbill.scaffold.manifest.appendCodeReviewArea
import skillbill.scaffold.manifest.appendExternalAddonManifestRegistration
import skillbill.scaffold.manifest.appendGovernedAddonManifestRegistration
import skillbill.scaffold.manifest.appendReadmeCatalogRow
import skillbill.scaffold.manifest.renderExternalAddonManifestRegistration
import skillbill.scaffold.manifest.renderGovernedAddonManifestRegistration
import skillbill.scaffold.manifest.renderReadmeCatalogRow
import skillbill.scaffold.manifest.setDeclaredQualityCheckFile
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.ScaffoldResult
import skillbill.scaffold.platformpack.discoverPlatformPackManifests
import skillbill.scaffold.platformpack.loadPlatformPack
import skillbill.scaffold.policy.scaffold.SKILL_KIND_ADD_ON
import skillbill.scaffold.policy.scaffold.SKILL_KIND_AGENT_ADDON
import skillbill.scaffold.policy.scaffold.SKILL_KIND_CODE_REVIEW_AREA
import skillbill.scaffold.policy.scaffold.SKILL_KIND_HORIZONTAL
import skillbill.scaffold.policy.scaffold.SKILL_KIND_PLATFORM_OVERRIDE_PILOTED
import skillbill.scaffold.policy.scaffold.SKILL_KIND_PLATFORM_PACK
import skillbill.scaffold.policy.scaffold.sharedContractNote
import skillbill.scaffold.rendering.defaultAreaFocus
import skillbill.scaffold.rendering.inferSkillDescription
import skillbill.scaffold.rendering.renderAddonBody
import skillbill.scaffold.rendering.renderContentBody
import skillbill.scaffold.rendering.renderNativeAgentBundleStubs
import java.nio.file.Files
import java.nio.file.Path
import skillbill.scaffold.payload.detectKind as policyDetectKind
import skillbill.scaffold.payload.optionalSpecialistSubagents as policyOptionalSpecialistSubagents
import skillbill.scaffold.payload.rejectBaselineLayersForNonPlatformPack as policyRejectBaselineLayersForNonPlatformPack
import skillbill.scaffold.payload.rejectLeafSubagentSpecialists as policyRejectLeafSubagentSpecialists
import skillbill.scaffold.payload.requireStringMap as requireString
import skillbill.scaffold.payload.requireStringOrDefaultMap as requireStringOrDefault
import skillbill.scaffold.payload.resolvePlatformPackDefaults as policyResolvePlatformPackDefaults
import skillbill.scaffold.payload.resolvePlatformPackSelection as policyResolvePlatformPackSelection
import skillbill.scaffold.payload.validatePayloadVersion as policyValidatePayloadVersion
import skillbill.scaffold.policy.platformpack.buildPlatformPackInstallPaths as policyBuildPlatformPackInstallPaths
import skillbill.scaffold.policy.platformpack.platformPackNotes as policyPlatformPackNotes
import skillbill.scaffold.policy.platformpack.renderPlatformPackManifestContent as policyRenderPlatformPackManifestContent
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.payload.requireStringListPayload

internal data class ManifestSnapshot(
  val manifestPath: Path,
  val originalBytes: ByteArray,
)

internal data class ScaffoldTransaction(
  val createdPaths: MutableList<Path> = mutableListOf(),
  val createdDirs: MutableList<Path> = mutableListOf(),
  val createdSymlinks: MutableList<Path> = mutableListOf(),
  val manifestSnapshots: MutableList<ManifestSnapshot> = mutableListOf(),
  val installTargets: MutableList<Path> = mutableListOf(),
)

internal data class ScaffoldPlan(
  val kind: String,
  val skillName: String,
  val skillPath: Path,
  val skillFile: Path,
  val contentFile: Path?,
  val family: String,
  val platform: String,
  val area: String,
  val isShelled: Boolean,
  val notes: List<String>,
  val displayName: String = "",
  val description: String = "",
  val manifestPath: Path? = null,
  val routingSignals: List<String> = emptyList(),
  val tieBreakers: List<String> = emptyList(),
  val specialistAreas: List<String> = emptyList(),
  val specialistAreaMetadata: Map<String, String> = emptyMap(),
  val specialistSkillNames: Map<String, String> = emptyMap(),
  val specialistSkillPaths: Map<String, Path> = emptyMap(),
  val baselineSkillName: String = "",
  val baselineSkillPath: Path? = null,
  val qualityCheckSkillName: String = "",
  val qualityCheckSkillPath: Path? = null,
  val installPaths: List<Path> = emptyList(),
  val createdFiles: List<Path> = emptyList(),
  val contentBody: String? = null,
  val addonBody: String? = null,
  val addonConsumerSkillDirs: List<String> = emptyList(),
  val agentIds: List<String> = emptyList(),
  val agentAddonConsumers: List<String> = emptyList(),
  val externalAddonLocationPath: Path? = null,
  val baselineLayers: List<CodeReviewBaselineLayer> = emptyList(),
  val subagentSpecialists: List<String> = emptyList(),
  val subagentDescriptions: Map<String, String> = emptyMap(),
  val bodyBasedSubagents: Set<String> = emptySet(),
  val subagentsSuppressed: Boolean = false,
)

internal data class ScaffoldExecutionResult(
  val createdFiles: List<Path>,
  val manifestEdits: List<Path>,
  val symlinks: List<Path>,
  val installTargets: List<Path>,
  val notes: List<String>,
)

/**
 * SKILL-52.1 subtask 3 (F-001): orchestrator entrypoint. Receives the two carved IO
 * validator adapters as explicit parameters so the DI-bound `FileSystemScaffoldOrchestrator`
 * can thread its kotlin-inject-provided singletons through; this eliminates the prior
 * file-static parallel instances. The adapters are passed as opaque seams via the
 * [ScaffoldAdapterSeams] holder so this file does not need to import the concrete adapter
 * class names directly.
 */
internal fun scaffoldWithAdapters(
  payload: Map<String, Any?>,
  dryRun: Boolean,
  adapters: ScaffoldAdapterSeams,
): ScaffoldResult {
  require(payload.isNotEmpty()) {
    "Scaffold payload must be a JSON object mapping string keys to values."
  }

  policyValidatePayloadVersion(payload)
  val kind = policyDetectKind(payload)
  val repoRoot = resolveRepoRoot(payload)
  val plan = planScaffold(payload, repoRoot, kind, adapters)
  return if (dryRun) {
    renderDryRunResult(plan, repoRoot)
  } else {
    runScaffold(plan, repoRoot, adapters)
  }
}

/**
 * Port-style adapter facades that decouple the orchestrator file from the concrete adapter
 * class names. `FileSystemScaffoldOrchestrator` (in `skillbill.infrastructure.fs`) binds
 * kotlin-inject-provided adapters into instances of this holder and threads them through
 * `scaffoldWithAdapters`. Keeping this seam local to `runtime-infra-fs` preserves the F-006
 * constraint that the carved validators remain `internal fun` on the adapter classes.
 */
internal data class ScaffoldAdapterSeams(
  val validateScaffold: (ScaffoldPlan, Path) -> Unit,
  val optionalBaselineLayers: (Map<String, Any?>, Path, String) -> List<CodeReviewBaselineLayer>,
  val resolveAddonConsumerSkillDirs: (
    Map<String, Any?>,
    Path,
    PlatformManifest,
  ) -> List<String>,
)
internal fun renderDryRunResult(plan: ScaffoldPlan, repoRoot: Path): ScaffoldResult = ScaffoldResult(
  kind = plan.kind,
  skillName = plan.skillName,
  skillPath = plan.skillPath,
  createdFiles = previewCreatedFiles(plan),
  manifestEdits = previewManifestEdits(plan, repoRoot),
  manifestPreviews = previewManifestPreviews(plan, repoRoot),
  symlinks = emptyList(),
  installTargets = emptyList(),
  notes = plan.notes + listOf("Dry run - no filesystem changes applied."),
)

internal fun runScaffold(plan: ScaffoldPlan, repoRoot: Path, adapters: ScaffoldAdapterSeams): ScaffoldResult {
  val txn = ScaffoldTransaction()
  return try {
    val execution = executeScaffold(txn, plan, repoRoot, adapters)
    ScaffoldResult(
      kind = plan.kind,
      skillName = plan.skillName,
      skillPath = plan.skillPath,
      createdFiles = execution.createdFiles,
      manifestEdits = execution.manifestEdits,
      symlinks = execution.symlinks,
      installTargets = execution.installTargets,
      notes = plan.notes + execution.notes,
    )
  } catch (error: Throwable) {
    rollback(txn)
    throw error
  }
}

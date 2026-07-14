package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.AgentAddonDeliveryResolver
import skillbill.agentaddon.inspectAgentAddons as inspectFsAgentAddons
import skillbill.agentaddon.model.AgentAddonCatalogueEntry
import skillbill.nativeagent.composition.NativeAgentCompositionDirective
import skillbill.nativeagent.composition.NativeAgentCompositionKind
import skillbill.nativeagent.composition.NativeAgentSource
import skillbill.ports.scaffold.RepoSourceDiscoveryGateway
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.ScaffoldGateway
import skillbill.ports.scaffold.UnsupportedScaffoldGateway
import skillbill.ports.scaffold.catalog.model.ScaffoldExplainResult
import skillbill.ports.scaffold.catalog.model.ScaffoldExplainSkill
import skillbill.ports.scaffold.catalog.model.ScaffoldListResult
import skillbill.ports.scaffold.catalog.model.ScaffoldShowResult
import skillbill.ports.scaffold.model.NativeAgentSourceProjection
import skillbill.ports.scaffold.model.PilotedPlatformPackProjection
import skillbill.ports.scaffold.model.ScaffoldRenderBlock
import skillbill.ports.scaffold.model.ScaffoldRenderResult
import skillbill.ports.scaffold.model.ScaffoldSkillStatus
import skillbill.ports.scaffold.repo.model.ScaffoldUpgradeResult
import skillbill.ports.scaffold.repo.model.ScaffoldValidateResult
import skillbill.ports.scaffold.source.model.ScaffoldEditWithBodyFileResult
import skillbill.ports.scaffold.source.model.ScaffoldFillResult
import skillbill.ports.scaffold.source.model.ScaffoldSaveExactContentResult
import skillbill.scaffold.authoring.AuthoringOperations
import skillbill.scaffold.authoring.AuthoringRenderResult
import skillbill.scaffold.authoring.recommendedCommands
import skillbill.scaffold.authoring.renderAuthoringTarget
import skillbill.scaffold.catalog.ScaffoldCatalog
import skillbill.scaffold.model.command.ScaffoldCommandRequest
import skillbill.scaffold.runtime.scaffold
import java.nio.file.Path
import skillbill.nativeagent.composition.parseNativeAgentSourceFile as parseFsNativeAgentSourceFile
import skillbill.nativeagent.composition.renderComposedNativeAgentSource as renderFsComposedNativeAgentSource
import skillbill.nativeagent.composition.renderNativeAgentSource as renderFsNativeAgentSource
import skillbill.nativeagent.discovery.discoverNativeAgentSourceFiles as discoverFsNativeAgentSourceFiles
import skillbill.ports.scaffold.model.GeneratedArtifactFile as PortGeneratedArtifactFile
import skillbill.scaffold.platformpack.discoverGovernedAddonFiles as discoverFsGovernedAddonFiles
import skillbill.scaffold.pointer.discoverGeneratedArtifactFiles as discoverFsGeneratedArtifactFiles

private const val CONTENT_PREVIEW_MAX_CHARS = 500

@Inject
class FileSystemScaffoldGateway(
  private val scaffoldOrchestrator: FileSystemScaffoldOrchestrator,
) : ScaffoldGateway {
  override fun list(repoRoot: Path, skillNames: List<String>): ScaffoldListResult {
    val addonNames = skillNames.filter { it.startsWith(AGENT_ADDON_PREFIX) }
    val governedNames = skillNames.filterNot { it.startsWith(AGENT_ADDON_PREFIX) }
    val result = AuthoringOperations.list(repoRoot, governedNames)
    val addons = AgentAddonDeliveryResolver().catalogue(repoRoot)
      .filter { skillNames.isEmpty() || it.identity in addonNames }
      .map { it.toSkillStatus(repoRoot, "none") }
    val governedSkills = if (skillNames.isEmpty()) {
      result.skills
    } else {
      result.skills.filter { it.skillName in governedNames }
    }
    val skills = governedSkills + addons
    return ScaffoldListResult(
      repoRoot = result.repoRoot,
      skillCount = skills.size,
      skills = skills,
    )
  }

  override fun show(repoRoot: Path, skillName: String, contentMode: String): ScaffoldShowResult = ScaffoldShowResult(
    status = if (skillName.startsWith(AGENT_ADDON_PREFIX)) {
      requireAgentAddonEntry(repoRoot, skillName).toSkillStatus(repoRoot, contentMode)
    } else {
      AuthoringOperations.show(repoRoot, skillName, contentMode)
    },
  )

  override fun explain(repoRoot: Path, skillName: String?): ScaffoldExplainResult {
    if (skillName?.startsWith(AGENT_ADDON_PREFIX) == true) {
      val addon = requireAgentAddonEntry(repoRoot, skillName)
      return ScaffoldExplainResult(
        explanation = "Agent add-ons are governed extension sources delivered to declared skill consumers.",
        editableSurface = listOf("agent-addon.yaml", "content.md"),
        generatedSurface = listOf("agent-addon-<slug>.md install pointers"),
        governedSidecars = emptyList(),
        normalWorkflow = listOf("skill-bill show $skillName", "skill-bill validate", "skill-bill render bill-feature"),
        notes = listOf(
          "Supported agents: ${addon.agentIds.joinToString()}",
          "Consumers: ${addon.consumers.joinToString()}",
        ),
        skill = ScaffoldExplainSkill(
          skillName = addon.identity,
          contentFile = addon.contentPath.toString(),
          renderCommand = "skill-bill render bill-feature --repo-root ${repoRoot.toAbsolutePath().normalize()}",
          recommendedCommands = listOf("skill-bill validate", "skill-bill render bill-feature"),
        ),
      )
    }
    val result = AuthoringOperations.explain(repoRoot, skillName)
    return ScaffoldExplainResult(
      explanation = result.explanation,
      editableSurface = result.editableSurface,
      generatedSurface = result.generatedSurface,
      governedSidecars = result.governedSidecars,
      normalWorkflow = result.normalWorkflow,
      notes = result.notes,
      skill = result.skill?.let { skill ->
        ScaffoldExplainSkill(
          skillName = skill.skillName,
          contentFile = skill.contentFile,
          renderCommand = skill.renderCommand,
          recommendedCommands = skill.recommendedCommands,
        )
      },
    )
  }

  override fun validate(repoRoot: Path, skillNames: List<String>): ScaffoldValidateResult {
    val result = AuthoringOperations.validate(repoRoot, skillNames)
    return ScaffoldValidateResult(
      repoRoot = result.repoRoot,
      mode = result.mode,
      status = result.status,
      issues = result.issues,
      skillNames = result.skillNames,
      suggestedCommands = result.suggestedCommands,
    )
  }

  override fun upgrade(repoRoot: Path, skillNames: List<String>, validate: Boolean): ScaffoldUpgradeResult {
    val result = AuthoringOperations.upgrade(repoRoot, skillNames, validate)
    return ScaffoldUpgradeResult(
      repoRoot = result.repoRoot,
      regeneratedCount = result.regeneratedCount,
      regeneratedFiles = result.regeneratedFiles,
      contentMdTouched = result.contentMdTouched,
      shellCeremonyTouched = result.shellCeremonyTouched,
      validatorRan = result.validatorRan,
    )
  }

  override fun fill(repoRoot: Path, skillName: String, body: String, sectionName: String?): ScaffoldFillResult {
    val result = AuthoringOperations.fill(repoRoot, skillName, body, sectionName)
    return ScaffoldFillResult(
      status = result.mutation.status,
      wrapperRegenerated = result.mutation.wrapperRegenerated,
      updatedSection = result.updatedSection,
      validatorRan = result.validatorRan,
    )
  }

  override fun saveExactContent(repoRoot: Path, skillName: String, content: String): ScaffoldSaveExactContentResult {
    val result = AuthoringOperations.saveExactContent(repoRoot, skillName, content)
    return ScaffoldSaveExactContentResult(
      status = result.mutation.status,
      wrapperRegenerated = result.mutation.wrapperRegenerated,
      validatorRan = result.validatorRan,
    )
  }

  override fun editWithBodyFile(
    repoRoot: Path,
    skillName: String,
    body: String,
    sectionName: String?,
  ): ScaffoldEditWithBodyFileResult {
    val result = AuthoringOperations.editWithBodyFile(repoRoot, skillName, body, sectionName)
    return ScaffoldEditWithBodyFileResult(
      usedEditor = result.usedEditor,
      guidedSections = result.guidedSections,
      updatedSection = result.updatedSection,
      validatorRan = result.validatorRan,
      status = result.mutation.status,
      wrapperRegenerated = result.mutation.wrapperRegenerated,
    )
  }

  /**
   * SKILL-52.2 subtask 2: typed scaffold entry point. The typed request is re-materialised into
   * the legacy raw-map payload shape and delegated to the existing scaffolder code path so
   * byte-equivalent outputs are trivially preserved (AC4); the re-materialisation happens
   * entirely inside `runtime-infra-fs`, which is outside the raw-map architecture scan scope.
   */
  override fun scaffold(request: ScaffoldCommandRequest, dryRun: Boolean) =
    scaffoldOrchestrator.scaffold(request, dryRun)

  override fun render(repoRoot: Path, skillName: String): ScaffoldRenderResult =
    renderAuthoringTarget(repoRoot, skillName).toPortRenderResult()
}

private const val AGENT_ADDON_PREFIX = "agent-addon:"

private fun requireAgentAddonEntry(repoRoot: Path, identity: String): AgentAddonCatalogueEntry =
  AgentAddonDeliveryResolver().catalogue(repoRoot).firstOrNull { it.identity == identity }
    ?: throw skillbill.error.MissingAgentAddonDeclarationError(
      identity.removePrefix(AGENT_ADDON_PREFIX),
      repoRoot.resolve("agent-addons").toString(),
    )

private fun AgentAddonCatalogueEntry.toSkillStatus(repoRoot: Path, contentMode: String): ScaffoldSkillStatus {
  val contentText = java.nio.file.Files.readString(contentPath)
  return ScaffoldSkillStatus(
    skillName = identity,
    packageName = "agent-addons",
    platform = "",
    family = "agent-addon",
    area = "",
    contentFile = contentPath.toString(),
    renderCommand = "skill-bill render bill-feature --repo-root ${repoRoot.toAbsolutePath().normalize()}",
    completionStatus = "authored",
    sectionCount = 0,
    sections = emptyList(),
    recommendedCommands = listOf("skill-bill validate", "skill-bill render bill-feature"),
    contentPreview = if (contentMode == "preview") contentText.take(CONTENT_PREVIEW_MAX_CHARS) else null,
    content = if (contentMode == "full") contentText else null,
    category = "agent-addon",
    slug = slug,
    description = description,
    supportedAgents = agentIds,
    consumers = consumers,
    manifestFile = manifestPath.toString(),
  )
}

@Inject
class FileSystemUnsupportedScaffoldGateway : UnsupportedScaffoldGateway {
  override fun retiredUnsupportedMessage(command: String, replacement: String, editor: Boolean) = if (editor) {
    AuthoringOperations.retiredEditorMessage(command, replacement)
  } else {
    AuthoringOperations.retiredInteractiveMessage(command, replacement)
  }
}

@Inject
class FileSystemScaffoldCatalogGateway : ScaffoldCatalogGateway {
  override fun approvedCodeReviewAreas() = ScaffoldCatalog.approvedCodeReviewAreas

  override fun preShellFamilies() = ScaffoldCatalog.preShellFamilies

  override fun shelledFamilies() = ScaffoldCatalog.shelledFamilies

  override fun platformPackPresets() = ScaffoldCatalog.platformPackPresets

  override fun scaffoldPayloadVersion() = ScaffoldCatalog.scaffoldPayloadVersion

  override fun discoverPilotedPlatformPacks(packsRoot: Path) =
    ScaffoldCatalog.discoverPilotedPlatformPacks(packsRoot).map { pack ->
      PilotedPlatformPackProjection(slug = pack.slug, displayName = pack.displayName)
    }

  override fun discoverPlatformManifests(packsRoot: Path) = ScaffoldCatalog.discoverPilotedPlatformPacks(packsRoot)

  override fun discoverBaselineReviewCatalog(packsRoot: Path) = ScaffoldCatalog.discoverBaselineReviewCatalog(packsRoot)
}

@Inject
class FileSystemRepoSourceDiscoveryGateway : RepoSourceDiscoveryGateway {
  override fun discoverAgentAddons(repoRoot: Path) = AgentAddonDeliveryResolver().catalogue(repoRoot)

  override fun inspectAgentAddons(repoRoot: Path) = inspectFsAgentAddons(repoRoot)

  override fun discoverGovernedAddonFiles(repoRoot: Path) = discoverFsGovernedAddonFiles(repoRoot)

  override fun discoverGeneratedArtifactFiles(repoRoot: Path): List<PortGeneratedArtifactFile> =
    discoverFsGeneratedArtifactFiles(repoRoot).map { artifact ->
      PortGeneratedArtifactFile(path = artifact.path, reason = artifact.reason)
    }

  override fun discoverNativeAgentSourceFiles(platformPacksRoot: Path, skillsRoot: Path?) =
    discoverFsNativeAgentSourceFiles(platformPacksRoot, skillsRoot)

  override fun parseNativeAgentSourceFile(path: Path): List<NativeAgentSourceProjection> =
    parseFsNativeAgentSourceFile(path).map(NativeAgentSource::toProjection)

  override fun renderNativeAgentSource(source: NativeAgentSourceProjection) =
    renderFsNativeAgentSource(source.toNativeAgentSource())

  override fun renderComposedNativeAgentSource(repoRoot: Path, source: NativeAgentSourceProjection) =
    renderFsComposedNativeAgentSource(repoRoot, source.toNativeAgentSource())
}

private fun NativeAgentSource.toProjection(): NativeAgentSourceProjection = NativeAgentSourceProjection(
  name = name,
  description = description,
  body = body,
  compositionKindWireValue = composition?.kind?.wireValue,
  path = path,
  bundleEntryName = bundleEntryName,
)

private fun NativeAgentSourceProjection.toNativeAgentSource(): NativeAgentSource = NativeAgentSource(
  name = name,
  description = description,
  body = body,
  composition = compositionKindWireValue
    ?.let { wireValue -> NativeAgentCompositionKind.entries.firstOrNull { it.wireValue == wireValue } }
    ?.let(::NativeAgentCompositionDirective),
  path = path,
  bundleEntryName = bundleEntryName,
)

private fun AuthoringRenderResult.toPortRenderResult(): ScaffoldRenderResult = ScaffoldRenderResult(
  repoRoot = repoRoot,
  skillName = skillName,
  blocks = blocks.map { block -> ScaffoldRenderBlock(header = block.header, content = block.content) },
)

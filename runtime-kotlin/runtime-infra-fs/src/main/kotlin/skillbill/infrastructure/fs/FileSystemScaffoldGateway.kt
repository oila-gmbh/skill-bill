package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.nativeagent.NativeAgentCompositionDirective
import skillbill.nativeagent.NativeAgentCompositionKind
import skillbill.nativeagent.NativeAgentSource
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
import skillbill.ports.scaffold.repo.model.ScaffoldUpgradeResult
import skillbill.ports.scaffold.repo.model.ScaffoldValidateResult
import skillbill.ports.scaffold.source.model.ScaffoldEditWithBodyFileResult
import skillbill.ports.scaffold.source.model.ScaffoldFillResult
import skillbill.ports.scaffold.source.model.ScaffoldSaveExactContentResult
import skillbill.scaffold.AuthoringOperations
import skillbill.scaffold.AuthoringRenderResult
import skillbill.scaffold.ScaffoldCatalog
import skillbill.scaffold.model.command.ScaffoldCommandRequest
import skillbill.scaffold.renderAuthoringTarget
import java.nio.file.Path
import skillbill.nativeagent.discoverNativeAgentSourceFiles as discoverFsNativeAgentSourceFiles
import skillbill.nativeagent.parseNativeAgentSourceFile as parseFsNativeAgentSourceFile
import skillbill.nativeagent.renderComposedNativeAgentSource as renderFsComposedNativeAgentSource
import skillbill.nativeagent.renderNativeAgentSource as renderFsNativeAgentSource
import skillbill.ports.scaffold.model.GeneratedArtifactFile as PortGeneratedArtifactFile
import skillbill.scaffold.discoverGeneratedArtifactFiles as discoverFsGeneratedArtifactFiles
import skillbill.scaffold.discoverGovernedAddonFiles as discoverFsGovernedAddonFiles

@Inject
class FileSystemScaffoldGateway(
  private val scaffoldOrchestrator: FileSystemScaffoldOrchestrator,
) : ScaffoldGateway {
  override fun list(repoRoot: Path, skillNames: List<String>): ScaffoldListResult {
    val result = AuthoringOperations.list(repoRoot, skillNames)
    return ScaffoldListResult(
      repoRoot = result.repoRoot,
      skillCount = result.skillCount,
      skills = result.skills,
    )
  }

  override fun show(repoRoot: Path, skillName: String, contentMode: String): ScaffoldShowResult =
    ScaffoldShowResult(status = AuthoringOperations.show(repoRoot, skillName, contentMode))

  override fun explain(repoRoot: Path, skillName: String?): ScaffoldExplainResult {
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

  override fun discoverBaselineReviewCatalog(packsRoot: Path) = ScaffoldCatalog.discoverBaselineReviewCatalog(packsRoot)
}

@Inject
class FileSystemRepoSourceDiscoveryGateway : RepoSourceDiscoveryGateway {
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

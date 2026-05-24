package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.nativeagent.NativeAgentCompositionDirective
import skillbill.nativeagent.NativeAgentCompositionKind
import skillbill.nativeagent.NativeAgentSource
import skillbill.ports.scaffold.RepoSourceDiscoveryGateway
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.ScaffoldGateway
import skillbill.ports.scaffold.UnsupportedScaffoldGateway
import skillbill.ports.scaffold.model.NativeAgentSourceProjection
import skillbill.ports.scaffold.model.PilotedPlatformPackProjection
import skillbill.ports.scaffold.model.ScaffoldRenderBlock
import skillbill.ports.scaffold.model.ScaffoldRenderResult
import skillbill.scaffold.AuthoringOperations
import skillbill.scaffold.AuthoringRenderResult
import skillbill.scaffold.ScaffoldCatalog
import skillbill.scaffold.renderAuthoringTarget
import java.nio.file.Path
import skillbill.nativeagent.discoverNativeAgentSourceFiles as discoverFsNativeAgentSourceFiles
import skillbill.nativeagent.parseNativeAgentSourceFile as parseFsNativeAgentSourceFile
import skillbill.nativeagent.renderComposedNativeAgentSource as renderFsComposedNativeAgentSource
import skillbill.nativeagent.renderNativeAgentSource as renderFsNativeAgentSource
import skillbill.ports.scaffold.model.GeneratedArtifactFile as PortGeneratedArtifactFile
import skillbill.scaffold.discoverGeneratedArtifactFiles as discoverFsGeneratedArtifactFiles
import skillbill.scaffold.discoverGovernedAddonFiles as discoverFsGovernedAddonFiles
import skillbill.scaffold.scaffold as scaffoldFs

@Inject
class FileSystemScaffoldGateway : ScaffoldGateway {
  override fun list(repoRoot: Path, skillNames: List<String>) = AuthoringOperations.list(repoRoot, skillNames)

  override fun show(repoRoot: Path, skillName: String, contentMode: String) =
    AuthoringOperations.show(repoRoot, skillName, contentMode)

  override fun explain(repoRoot: Path, skillName: String?) = AuthoringOperations.explain(repoRoot, skillName)

  override fun validate(repoRoot: Path, skillNames: List<String>) = AuthoringOperations.validate(repoRoot, skillNames)

  override fun upgrade(repoRoot: Path, skillNames: List<String>, validate: Boolean) =
    AuthoringOperations.upgrade(repoRoot, skillNames, validate)

  override fun fill(repoRoot: Path, skillName: String, body: String, sectionName: String?) =
    AuthoringOperations.fill(repoRoot, skillName, body, sectionName)

  override fun saveExactContent(repoRoot: Path, skillName: String, content: String) =
    AuthoringOperations.saveExactContent(repoRoot, skillName, content)

  override fun editWithBodyFile(repoRoot: Path, skillName: String, body: String, sectionName: String?) =
    AuthoringOperations.editWithBodyFile(repoRoot, skillName, body, sectionName)

  override fun scaffold(payload: Map<String, Any?>, dryRun: Boolean) = scaffoldFs(payload, dryRun)

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

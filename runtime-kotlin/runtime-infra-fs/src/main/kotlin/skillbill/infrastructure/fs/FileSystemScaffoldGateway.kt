package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.error.InvalidScaffoldPayloadError
import skillbill.nativeagent.NativeAgentCompositionDirective
import skillbill.nativeagent.NativeAgentCompositionKind
import skillbill.nativeagent.NativeAgentSource
import skillbill.ports.scaffold.RepoSourceDiscoveryGateway
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.ScaffoldGateway
import skillbill.ports.scaffold.UnsupportedScaffoldGateway
import skillbill.ports.scaffold.catalog.model.ScaffoldExplainResult
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
    val payload = AuthoringOperations.list(repoRoot, skillNames)
    return ScaffoldListResult(
      repoRoot = payload.requireScalar<String>("list", "repo_root"),
      skillCount = payload.requireInt("list", "skill_count"),
      payload = payload,
    )
  }

  override fun show(repoRoot: Path, skillName: String, contentMode: String): ScaffoldShowResult {
    val payload = AuthoringOperations.show(repoRoot, skillName, contentMode)
    return ScaffoldShowResult(
      skillName = payload.requireScalar<String>("show", "skill_name"),
      payload = payload,
    )
  }

  override fun explain(repoRoot: Path, skillName: String?): ScaffoldExplainResult =
    ScaffoldExplainResult(payload = AuthoringOperations.explain(repoRoot, skillName))

  override fun validate(repoRoot: Path, skillNames: List<String>): ScaffoldValidateResult {
    val payload = AuthoringOperations.validate(repoRoot, skillNames)
    return ScaffoldValidateResult(
      status = payload.requireScalar<String>("validate", "status"),
      payload = payload,
    )
  }

  override fun upgrade(repoRoot: Path, skillNames: List<String>, validate: Boolean): ScaffoldUpgradeResult {
    val payload = AuthoringOperations.upgrade(repoRoot, skillNames, validate)
    return ScaffoldUpgradeResult(
      regeneratedCount = payload.requireInt("upgrade", "regenerated_count"),
      validatorRan = payload.requireScalar<Boolean>("upgrade", "validator_ran"),
      payload = payload,
    )
  }

  override fun fill(repoRoot: Path, skillName: String, body: String, sectionName: String?): ScaffoldFillResult {
    val payload = AuthoringOperations.fill(repoRoot, skillName, body, sectionName)
    return ScaffoldFillResult(
      skillName = payload.requireScalar<String>("fill", "skill_name"),
      validatorRan = payload.requireScalar<Boolean>("fill", "validator_ran"),
      payload = payload,
    )
  }

  override fun saveExactContent(repoRoot: Path, skillName: String, content: String): ScaffoldSaveExactContentResult {
    val payload = AuthoringOperations.saveExactContent(repoRoot, skillName, content)
    return ScaffoldSaveExactContentResult(
      skillName = payload.requireScalar<String>("saveExactContent", "skill_name"),
      validatorRan = payload.requireScalar<Boolean>("saveExactContent", "validator_ran"),
      payload = payload,
    )
  }

  override fun editWithBodyFile(
    repoRoot: Path,
    skillName: String,
    body: String,
    sectionName: String?,
  ): ScaffoldEditWithBodyFileResult {
    val payload = AuthoringOperations.editWithBodyFile(repoRoot, skillName, body, sectionName)
    return ScaffoldEditWithBodyFileResult(
      skillName = payload.requireScalar<String>("editWithBodyFile", "skill_name"),
      usedEditor = payload.requireScalar<Boolean>("editWithBodyFile", "used_editor"),
      validatorRan = payload.requireScalar<Boolean>("editWithBodyFile", "validator_ran"),
      payload = payload,
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

/**
 * SKILL-52.1 subtask 3 (F-003 + F-012): typed lift helper that replaces unchecked
 * `as <Type>` casts when extracting scalars from the legacy raw-map payloads produced by
 * `AuthoringOperations`. A typed mismatch surfaces as [InvalidScaffoldPayloadError] with
 * an actionable message naming the producer operation and the offending key, instead of
 * the opaque `ClassCastException` the bare casts produced.
 */
private inline fun <reified T : Any> Map<String, Any?>.requireScalar(op: String, key: String): T {
  val value = this[key]
    ?: throw InvalidScaffoldPayloadError(
      "AuthoringOperations.$op payload missing/typed mismatch on '$key' (expected ${T::class.simpleName}).",
    )
  if (value !is T) {
    throw InvalidScaffoldPayloadError(
      "AuthoringOperations.$op payload missing/typed mismatch on '$key' " +
        "(expected ${T::class.simpleName}, got ${value::class.simpleName}).",
    )
  }
  return value
}

/**
 * SKILL-52.1 subtask 3 (F-012): tolerate `Number` widening for `Int` lifts so a
 * JSON round-trip that produced a `Long` (e.g. from the MCP envelope re-encode path) still
 * lifts cleanly into the typed model field.
 */
private fun Map<String, Any?>.requireInt(op: String, key: String): Int {
  val value = this[key]
    ?: throw InvalidScaffoldPayloadError(
      "AuthoringOperations.$op payload missing/typed mismatch on '$key' (expected Int).",
    )
  if (value !is Number) {
    throw InvalidScaffoldPayloadError(
      "AuthoringOperations.$op payload missing/typed mismatch on '$key' " +
        "(expected Number-convertible Int, got ${value::class.simpleName}).",
    )
  }
  return value.toInt()
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

package skillbill.ports.scaffold

import skillbill.ports.scaffold.model.GeneratedArtifactFile
import skillbill.ports.scaffold.model.NativeAgentSourceProjection
import skillbill.ports.scaffold.model.PilotedPlatformPackProjection
import skillbill.ports.scaffold.model.ScaffoldRenderResult
import skillbill.scaffold.model.BaselineReviewCatalog
import skillbill.scaffold.model.GovernedAddonFile
import skillbill.scaffold.model.ScaffoldResult
import java.nio.file.Path

interface ScaffoldGateway {
  fun list(repoRoot: Path, skillNames: List<String>): Map<String, Any?>

  fun show(repoRoot: Path, skillName: String, contentMode: String): Map<String, Any?>

  fun explain(repoRoot: Path, skillName: String?): Map<String, Any?>

  fun validate(repoRoot: Path, skillNames: List<String>): Map<String, Any?>

  fun upgrade(repoRoot: Path, skillNames: List<String>, validate: Boolean): Map<String, Any?>

  fun fill(repoRoot: Path, skillName: String, body: String, sectionName: String?): Map<String, Any?>

  fun saveExactContent(repoRoot: Path, skillName: String, content: String): Map<String, Any?>

  fun editWithBodyFile(repoRoot: Path, skillName: String, body: String, sectionName: String?): Map<String, Any?>

  fun scaffold(payload: Map<String, Any?>, dryRun: Boolean): ScaffoldResult

  fun render(repoRoot: Path, skillName: String): ScaffoldRenderResult
}

interface ScaffoldCatalogGateway {
  fun approvedCodeReviewAreas(): Set<String>

  fun preShellFamilies(): Set<String>

  fun shelledFamilies(): Set<String>

  fun platformPackPresets(): Map<String, String>

  fun scaffoldPayloadVersion(): String

  fun discoverPilotedPlatformPacks(packsRoot: Path): List<PilotedPlatformPackProjection>

  fun discoverBaselineReviewCatalog(packsRoot: Path): BaselineReviewCatalog
}

interface RepoSourceDiscoveryGateway {
  fun discoverGovernedAddonFiles(repoRoot: Path): List<GovernedAddonFile>

  fun discoverGeneratedArtifactFiles(repoRoot: Path): List<GeneratedArtifactFile>

  fun discoverNativeAgentSourceFiles(platformPacksRoot: Path, skillsRoot: Path?): List<Path>

  fun parseNativeAgentSourceFile(path: Path): List<NativeAgentSourceProjection>

  fun renderNativeAgentSource(source: NativeAgentSourceProjection): String

  fun renderComposedNativeAgentSource(repoRoot: Path, source: NativeAgentSourceProjection): String
}

interface UnsupportedScaffoldGateway {
  fun retiredUnsupportedMessage(command: String, replacement: String, editor: Boolean): String
}

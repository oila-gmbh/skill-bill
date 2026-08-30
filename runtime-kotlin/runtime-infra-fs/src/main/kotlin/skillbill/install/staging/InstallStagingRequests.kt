package skillbill.install.staging

import skillbill.agentaddon.AgentAddonPointer
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import java.nio.file.Path

internal data class ReuseInstallStagingInput(
  val sourceSkillDir: Path,
  val finalStagingDir: Path,
  val contentHash: String,
  val applicablePointers: List<Pair<PlatformManifest, PointerSpec>>,
  val generatedSupportPointers: List<GeneratedSupportPointer> = emptyList(),
  val internalSidecarNames: Set<String> = emptySet(),
  val agentAddonPointerNames: List<String> = emptyList(),
)

internal data class ResolveStageInstalledSkillArtifactsInput(
  val resolvedSource: Path,
  val input: StageInstalledSkillInput,
  val authored: List<Path>,
  val pointers: List<Pair<PlatformManifest, PointerSpec>>,
  val internal: PreparedInternalStaging,
  val agentAddonPointers: List<AgentAddonPointer>,
  val suppliedCompactIdentity: String?,
)

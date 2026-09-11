package skillbill.infrastructure.fs.install.staging

import skillbill.infrastructure.fs.agentaddon.AgentAddonPointer
import skillbill.infrastructure.fs.install.identity.SkillContentIdentity
import skillbill.infrastructure.fs.install.support.writeRenderedSupportPointerFiles
import skillbill.infrastructure.fs.scaffold.authoring.AuthoringTarget
import skillbill.install.model.RenderedSkill
<<<<<<<< HEAD:runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/infrastructure/fs/install/staging/InstallStagingFreshSupport.kt
import skillbill.install.support.writeRenderedSupportPointerFiles
import skillbill.scaffold.authoring.AuthoringTarget
========
import skillbill.ports.repository.toFileLocation
>>>>>>>> 9d724a13f (SKILL-233: Engine module and package roots):runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/infrastructure/fs/install/staging/InstallStagingFresh.kt
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import java.nio.file.Files
import java.nio.file.Path

internal data class FreshInstallStagingArtifacts(
  val copiedInTemp: List<Path>,
  val skillFileInTemp: Path,
  val pointerFilesInTemp: List<Path>,
  val supportPointerFilesInTemp: List<Path>,
  val agentAddonFilesInTemp: List<Path>,
  val sidecarFilesInTemp: List<Path>,
)

internal fun populateFreshInstallStagingTemp(inputs: FreshInstallInputs, tempDir: Path): FreshInstallStagingArtifacts {
  val copiedInTemp = copyAuthoredIntoStaging(inputs.sourceSkillDir, tempDir, inputs.authored)
  val skillFileInTemp = writeRenderedSkillFile(tempDir, inputs.target)
  val pointerFilesInTemp = writeRenderedPointerFiles(inputs.repoRoot, tempDir, inputs.platformPointers)
  val supportPointerFilesInTemp = writeRenderedSupportPointerFiles(
    repoRoot = inputs.repoRoot,
    sourceSkillDir = inputs.sourceSkillDir,
    tempDir = tempDir,
    pointers = inputs.supportPointers,
  )
  val agentAddonFilesInTemp = writeAgentAddonPointerFiles(tempDir, inputs.agentAddonPointers)
  val sidecarFilesInTemp = writeInternalSidecarFiles(
    tempDir = tempDir,
    parentSourceDir = inputs.sourceSkillDir,
    children = inputs.internalChildren,
  )
  val packsRoot = inputs.repoRoot.resolve("platform-packs")
  if (Files.isDirectory(packsRoot)) {
    Files.createSymbolicLink(tempDir.resolve("platform-packs"), packsRoot)
  }
  writeInstallStagingMarkers(tempDir, inputs)
  return FreshInstallStagingArtifacts(
    copiedInTemp = copiedInTemp,
    skillFileInTemp = skillFileInTemp,
    pointerFilesInTemp = pointerFilesInTemp,
    supportPointerFilesInTemp = supportPointerFilesInTemp,
    agentAddonFilesInTemp = agentAddonFilesInTemp,
    sidecarFilesInTemp = sidecarFilesInTemp,
  )
}

internal fun finalizeFreshInstallStaging(
  inputs: FreshInstallInputs,
  tempDir: Path,
  staged: FreshInstallStagingArtifacts,
): RenderedSkill {
  val finalSkillFile = inputs.finalStagingDir.resolve(tempDir.relativize(staged.skillFileInTemp))
  val finalPointerFiles = (
    staged.pointerFilesInTemp + staged.supportPointerFilesInTemp + staged.agentAddonFilesInTemp
    )
    .map { path -> inputs.finalStagingDir.resolve(tempDir.relativize(path)) }
  val finalCopied = staged.copiedInTemp.map { path -> inputs.finalStagingDir.resolve(tempDir.relativize(path)) }
  val finalSidecars = staged.sidecarFilesInTemp.map { path ->
    inputs.finalStagingDir.resolve(tempDir.relativize(path))
  }
  pruneStaleStagingDirs(inputs.home, inputs.sourceSkillDir, inputs.contentHash)
  return RenderedSkill(
    skillName = inputs.sourceSkillDir.fileName.toString(),
    sourceSkillDir = inputs.sourceSkillDir,
    stagingDir = inputs.finalStagingDir,
    renderedSkillFile = finalSkillFile,
    renderedPointerFiles = finalPointerFiles,
    copiedAuthoredFiles = finalCopied,
    contentHash = inputs.contentHash,
    renderedSidecarFiles = finalSidecars,
  )
}

internal data class FreshInstallInputs(
  val home: Path,
  val sourceSkillDir: Path,
  val repoRoot: Path,
  val target: AuthoringTarget,
  val platformPointers: List<Pair<PlatformManifest, PointerSpec>>,
  val supportPointers: List<GeneratedSupportPointer>,
  val authored: List<Path>,
  val contentHash: String,
  val contentIdentity: SkillContentIdentity,
  val finalStagingDir: Path,
  val internalChildren: List<InternalSidecarTarget>,
  val agentAddonPointers: List<AgentAddonPointer>,
)
